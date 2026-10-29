package com.dhj.actinium.texture.atlas;

import net.minecraft.client.resources.FallbackResourceManager;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the trust gate that decides whether a resource domain may be read on worker threads.
 */
class AtlasPackPolicyTest {

    private static final String DOMAIN = "minecraft";

    /** A pack whose class the policy has never heard of, which is how a mod-provided pack looks to it. */
    private static final class UnknownPack implements IResourcePack {

        @Override
        public InputStream getInputStream(ResourceLocation location) throws IOException {
            throw new IOException("not a real pack");
        }

        @Override
        public boolean resourceExists(ResourceLocation location) {
            return false;
        }

        @Override
        public Set<String> getResourceDomains() {
            return Set.of(DOMAIN);
        }

        @Override
        public <T extends IMetadataSection> T getPackMetadata(MetadataSerializer serializer, String sectionName) {
            return null;
        }

        @Override
        public BufferedImage getPackImage() throws IOException {
            throw new IOException("not a real pack");
        }

        @Override
        public String getPackName() {
            return "unknown pack";
        }
    }

    /** A pack whose lookup blows up, which must make its domain load serially. */
    private static final class ExplodingFolderPack extends FolderResourcePack {

        ExplodingFolderPack() {
            super(new File("."));
        }

        @Override
        public boolean resourceExists(ResourceLocation location) {
            throw new IllegalStateException("lookup failed");
        }
    }

    @Test
    void aVanillaFolderPackIsEligible() {
        final Map<String, List<String>> untrusted = new HashMap<>();

        assertTrue(AtlasPackPolicy.checkDomain(DOMAIN, domainWith(new FolderResourcePack(new File("."))),
            Set.of(), Set.of(), untrusted));
        assertTrue(untrusted.isEmpty());
    }

    @Test
    void anUnknownPackClassMakesItsDomainSerial() {
        final Map<String, List<String>> untrusted = new HashMap<>();

        assertFalse(AtlasPackPolicy.checkDomain(DOMAIN, domainWith(new UnknownPack()), Set.of(), Set.of(), untrusted));
        assertEquals(List.of(DOMAIN), untrusted.get(UnknownPack.class.getName()),
            "the observed pack class has to be reported so the pack screen can offer it");
    }

    @Test
    void aTrustedUnknownPackClassIsEligible() {
        final Map<String, List<String>> untrusted = new HashMap<>();

        assertTrue(AtlasPackPolicy.checkDomain(DOMAIN, domainWith(new UnknownPack()),
            Set.of(UnknownPack.class.getName()), Set.of(), untrusted));
        assertTrue(untrusted.isEmpty());
    }

    @Test
    void aDistrustedPackClassWinsOverEverythingElse() {
        final Map<String, List<String>> untrusted = new HashMap<>();

        assertFalse(AtlasPackPolicy.checkDomain(DOMAIN, domainWith(new UnknownPack()),
            Set.of(UnknownPack.class.getName()), Set.of(UnknownPack.class.getName()), untrusted));
        assertTrue(untrusted.isEmpty(), "a pack the player refused is not offered again");
    }

    /** One broken pack in a domain has to keep the whole domain serial, since the packs are consulted together. */
    @Test
    void aFailingLookupKeepsTheDomainSerial() {
        final Map<String, List<String>> untrusted = new HashMap<>();

        assertFalse(AtlasPackPolicy.checkDomain(DOMAIN,
            domainWith(new FolderResourcePack(new File(".")), new ExplodingFolderPack()), Set.of(), Set.of(), untrusted));
    }

    private static FallbackResourceManager domainWith(IResourcePack... packs) {
        final FallbackResourceManager domain = new FallbackResourceManager(new MetadataSerializer());
        for (IResourcePack pack : packs) {
            domain.addResourcePack(pack);
        }
        return domain;
    }
}
