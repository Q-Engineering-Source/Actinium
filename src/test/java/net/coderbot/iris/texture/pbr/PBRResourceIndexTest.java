package net.coderbot.iris.texture.pbr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.minecraft.client.resources.FallbackResourceManager;
import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link PBRResourceIndex}: the index has to agree with what the resource manager would actually
 * serve, for zip packs, folder packs, packs that only exist in one domain, and the lazy/failed paths.
 */
class PBRResourceIndexTest {

    @TempDir
    Path tmp;

    private final List<FileResourcePack> zipPacks = new ArrayList<>();

    @AfterEach
    void cleanup() throws IOException {
        PBRResourceIndex.drop();
        for (FileResourcePack pack : zipPacks) {
            pack.close();
        }
    }

    private static final class StubPack implements IResourcePack {

        private final Set<ResourceLocation> claims = new HashSet<>();

        StubPack(ResourceLocation... locations) {
            Collections.addAll(claims, locations);
        }

        @Override
        public InputStream getInputStream(ResourceLocation location) throws IOException {
            return new ByteArrayInputStream("stub".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean resourceExists(ResourceLocation location) {
            return claims.contains(location);
        }

        @Override
        public Set<String> getResourceDomains() {
            return Collections.emptySet();
        }

        @Override
        public IMetadataSection getPackMetadata(MetadataSerializer serializer, String name) {
            return null;
        }

        @Override
        public BufferedImage getPackImage() {
            return null;
        }

        @Override
        public String getPackName() {
            return "stub";
        }
    }

    private FileResourcePack zip(String name, String... entries) throws IOException {
        final File file = tmp.resolve(name).toFile();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(file))) {
            for (String entry : entries) {
                zos.putNextEntry(new ZipEntry(entry));
                if (!entry.endsWith("/")) {
                    zos.write(entry.getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        final FileResourcePack pack = new FileResourcePack(file);
        zipPacks.add(pack);
        return pack;
    }

    private FolderResourcePack folder(String name, String... files) throws IOException {
        final Path root = tmp.resolve(name);
        for (String file : files) {
            final Path target = root.resolve(file);
            Files.createDirectories(target.getParent());
            Files.write(target, file.getBytes(StandardCharsets.UTF_8));
        }
        Files.createDirectories(root);
        return new FolderResourcePack(root.toFile());
    }

    private static FallbackResourceManager domain(MetadataSerializer serializer, IResourcePack... packs) {
        final FallbackResourceManager manager = new FallbackResourceManager(serializer);
        for (IResourcePack pack : packs) {
            manager.resourcePacks.add(pack);
        }
        return manager;
    }

    private static boolean loads(SimpleReloadableResourceManager manager, ResourceLocation location) {
        try (IResource resource = manager.getResource(location)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static final class TrackingResource implements IResource {
        private final ResourceLocation location;
        private int closeCount;

        private TrackingResource(ResourceLocation location) {
            this.location = location;
        }

        @Override
        public ResourceLocation getResourceLocation() {
            return location;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public boolean hasMetadata() {
            return false;
        }

        @Override
        public <T extends IMetadataSection> T getMetadata(String sectionName) {
            return null;
        }

        @Override
        public String getResourcePackName() {
            return "tracking";
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class TrackingResourceManager implements IResourceManager {
        private final ResourceLocation available;
        private final TrackingResource resource;

        private TrackingResourceManager(ResourceLocation available) {
            this.available = available;
            this.resource = new TrackingResource(available);
        }

        @Override
        public Set<String> getResourceDomains() {
            return Collections.singleton(available.getNamespace());
        }

        @Override
        public IResource getResource(ResourceLocation location) throws IOException {
            if (available.equals(location)) {
                return resource;
            }
            throw new FileNotFoundException(location.toString());
        }

        @Override
        public List<IResource> getAllResources(ResourceLocation location) throws IOException {
            if (available.equals(location)) {
                return Collections.singletonList(resource);
            }
            throw new FileNotFoundException(location.toString());
        }
    }

    /**
     * The folder scan mirrors the pack's own name lookup, which resolves a differently-cased name on a
     * case-insensitive filesystem; the index is then allowed to be more permissive, which costs one
     * extra load attempt and never hides a texture.
     */
    private static final boolean CASE_INSENSITIVE_FS = probeCaseInsensitiveFs();

    private static boolean probeCaseInsensitiveFs() {
        try {
            final Path file = Files.createTempFile("pbr-index-probe", ".txt");
            try {
                return Files.exists(file.resolveSibling(file.getFileName().toString().toUpperCase(Locale.ROOT)));
            } finally {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not probe the filesystem's case sensitivity", e);
        }
    }

    private static void assertAgrees(SimpleReloadableResourceManager manager, ResourceLocation location) {
        final boolean loaded = loads(manager, location);
        final boolean mayExist = PBRResourceIndex.mayExist(manager, location);
        if (mayExist == loaded) return;
        assertTrue(CASE_INSENSITIVE_FS && mayExist && !loaded,
            location + ": index=" + mayExist + " resourceManager=" + loaded + " caseInsensitiveFs=" + CASE_INSENSITIVE_FS);
    }

    private SimpleReloadableResourceManager build(MetadataSerializer serializer) throws IOException {
        final ResourceLocation stubbed = new ResourceLocation("minecraft", "textures/blocks/stub_n.png");
        final ResourceLocation stubbedShadowed = new ResourceLocation("minecraft", "textures/blocks/lowzip_s.png");
        final FileResourcePack low = zip(
            "low.zip",
            "assets/minecraft/textures/blocks/lowzip_n.png",
            "assets/minecraft/textures/blocks/lowzip_s.png",
            "assets/minecraft/textures/blocks/both_n.png",
            "assets/minecraft/textures/blocks/dirlike_n.png/",
            "assets/minecraft/textures/blocks/plain.png",
            "assets/minecraft/textures/blocks/notpbr_x.png",
            "assets/minecraft/textures/blocks/tail_n.png.bak",
            "assets/other/textures/blocks/other_n.png",
            "pack.mcmeta");
        final FolderResourcePack mid = folder(
            "mid",
            "assets/minecraft/textures/blocks/folder_s.png",
            "assets/minecraft/textures/blocks/both_n.png",
            "assets/minecraft/textures/blocks/Mixed_n.png",
            "assets/minecraft/textures/blocks/deep/er/nested_n.png",
            "assets/other/textures/blocks/otherfolder_s.png");
        final StubPack high = new StubPack(stubbed, stubbedShadowed);

        final SimpleReloadableResourceManager manager = new SimpleReloadableResourceManager(serializer);
        manager.domainResourceManagers.put("minecraft", domain(serializer, low, mid, high));
        manager.domainResourceManagers.put("other", domain(serializer, low, mid));
        manager.domainResourceManagers.put("stubonly", domain(serializer, new StubPack(new ResourceLocation("stubonly", "textures/x_n.png"))));
        return manager;
    }

    @Test
    void agreesWithGetResourceAcrossPackTypesAndDomains() throws Exception {
        final MetadataSerializer serializer = new MetadataSerializer();
        final SimpleReloadableResourceManager manager = build(serializer);
        PBRResourceIndex.start(manager);

        final String[][] locations = {
            { "minecraft", "textures/blocks/lowzip_n.png" },
            { "minecraft", "textures/blocks/lowzip_s.png" },
            { "minecraft", "textures/blocks/both_n.png" },
            { "minecraft", "textures/blocks/both_s.png" },
            { "minecraft", "textures/blocks/dirlike_n.png" },
            { "minecraft", "textures/blocks/folder_s.png" },
            { "minecraft", "textures/blocks/folder_n.png" },
            { "minecraft", "textures/blocks/Mixed_n.png" },
            { "minecraft", "textures/blocks/mixed_n.png" },
            { "minecraft", "textures/blocks/MIXED_n.png" },
            { "minecraft", "textures/blocks/deep/er/nested_n.png" },
            { "minecraft", "textures/blocks/deep/nested_n.png" },
            { "minecraft", "textures/blocks/stub_n.png" },
            { "minecraft", "textures/blocks/stub_s.png" },
            { "minecraft", "textures/blocks/absent_n.png" },
            { "minecraft", "textures/blocks/absent_s.png" },
            { "minecraft", "textures/blocks/tail_n.png" },
            { "other", "textures/blocks/other_n.png" },
            { "other", "textures/blocks/otherfolder_s.png" },
            { "other", "textures/blocks/stub_n.png" },
            { "other", "textures/blocks/lowzip_n.png" },
            { "stubonly", "textures/x_n.png" },
            { "stubonly", "textures/y_n.png" },
            { "ghost", "textures/blocks/lowzip_n.png" },
        };
        for (String[] location : locations) {
            assertAgrees(manager, new ResourceLocation(location[0], location[1]));
        }
    }

    @Test
    void presentLocationsAreReportedPresent() throws Exception {
        final SimpleReloadableResourceManager manager = build(new MetadataSerializer());
        PBRResourceIndex.start(manager);

        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/lowzip_n.png")));
        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/folder_s.png")));
        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/stub_n.png")));
        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("other", "textures/blocks/otherfolder_s.png")));
        assertFalse(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/absent_n.png")));
        assertFalse(PBRResourceIndex.mayExist(manager, new ResourceLocation("other", "textures/blocks/stub_n.png")));
        assertFalse(PBRResourceIndex.mayExist(manager, new ResourceLocation("ghost", "textures/blocks/lowzip_n.png")));
    }

    @Test
    void respectsResourcePackPriority() throws Exception {
        final SimpleReloadableResourceManager manager = build(new MetadataSerializer());
        PBRResourceIndex.start(manager);
        final ResourceLocation shared = new ResourceLocation("minecraft", "textures/blocks/both_n.png");

        assertTrue(PBRResourceIndex.mayExist(manager, shared));
        try (IResource resource = manager.getResource(shared)) {
            assertEquals("mid", resource.getResourcePackName());
        }
    }

    @Test
    void keepsDirectionalSiblingCheckAfterIndexReportsCandidatePresent() throws Exception {
        final MetadataSerializer serializer = new MetadataSerializer();
        final FolderResourcePack folder = folder(
            "directional",
            "assets/minecraft/textures/blocks/side_n.png",
            "assets/minecraft/textures/blocks/side_e.png");
        final SimpleReloadableResourceManager manager = new SimpleReloadableResourceManager(serializer);
        manager.domainResourceManagers.put("minecraft", domain(serializer, folder));
        PBRResourceIndex.start(manager);
        final ResourceLocation candidate = new ResourceLocation("minecraft", "textures/blocks/side_n.png");

        assertTrue(PBRResourceIndex.mayExist(manager, candidate));
        assertTrue(PBRType.hasDirectionalSiblings(candidate, manager));
    }

    @Test
    void directionalSiblingProbeClosesFoundResources() {
        final ResourceLocation sibling = new ResourceLocation("minecraft", "textures/blocks/side_e.png");
        final TrackingResourceManager manager = new TrackingResourceManager(sibling);
        final ResourceLocation candidate = new ResourceLocation("minecraft", "textures/blocks/side_n.png");

        assertTrue(PBRType.hasDirectionalSiblings(candidate, manager));
        assertEquals(1, manager.resource.closeCount);
    }

    @Test
    void reportsIndexLookupCounters() throws Exception {
        final SimpleReloadableResourceManager manager = build(new MetadataSerializer());
        PBRResourceIndex.start(manager);
        PBRResourceIndex.statsSummary();

        assertFalse(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/absent_n.png")));
        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/both_n.png")));
        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/both_n.png")));
        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/plain.png")));

        final String summary = PBRResourceIndex.statsSummary();
        assertTrue(summary.startsWith("pbr[lookups=3,memoHits=1,answeredAbsent=1,notPbr=1,directPackChecks=2,state=ready(packs="), summary);
        assertTrue(summary.endsWith(")]"), summary);
    }

    @Test
    void nonPbrLocationsAreNeverRuledOut() throws Exception {
        final SimpleReloadableResourceManager manager = build(new MetadataSerializer());
        PBRResourceIndex.start(manager);

        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/plain.png")));
        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/nothing.png")));
        assertFalse(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/absent_n.png")));
    }

    @Test
    void lazyBuildWithoutStartAgrees() throws Exception {
        final SimpleReloadableResourceManager manager = build(new MetadataSerializer());

        assertAgrees(manager, new ResourceLocation("minecraft", "textures/blocks/lowzip_n.png"));
        assertAgrees(manager, new ResourceLocation("minecraft", "textures/blocks/absent_n.png"));
    }

    @Test
    void startAfterReloadSeesNewPacks() throws Exception {
        final MetadataSerializer serializer = new MetadataSerializer();
        final SimpleReloadableResourceManager manager = build(serializer);
        PBRResourceIndex.start(manager);
        final ResourceLocation added = new ResourceLocation("minecraft", "textures/blocks/added_n.png");
        assertFalse(PBRResourceIndex.mayExist(manager, added));

        manager.domainResourceManagers.get("minecraft").resourcePacks.add(zip("added.zip", "assets/minecraft/textures/blocks/added_n.png"));
        PBRResourceIndex.start(manager);

        assertTrue(PBRResourceIndex.mayExist(manager, added));
        assertAgrees(manager, added);
    }

    @Test
    void buildFailureDisablesIndex() throws Exception {
        final MetadataSerializer serializer = new MetadataSerializer();
        final SimpleReloadableResourceManager manager = new SimpleReloadableResourceManager(serializer);
        final FileResourcePack missing = new FileResourcePack(tmp.resolve("missing.zip").toFile());
        manager.domainResourceManagers.put("minecraft", domain(serializer, missing));
        PBRResourceIndex.start(manager);

        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/absent_n.png")));
        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("ghost", "textures/blocks/absent_n.png")));
    }

    @Test
    void zipScanSharesThePacksOpenJar() throws Exception {
        final MetadataSerializer serializer = new MetadataSerializer();
        final SimpleReloadableResourceManager manager = new SimpleReloadableResourceManager(serializer);
        final FileResourcePack pack = zip("shared.zip", "assets/minecraft/textures/blocks/shared_n.png");
        manager.domainResourceManagers.put("minecraft", domain(serializer, pack));
        PBRResourceIndex.start(manager);

        assertTrue(PBRResourceIndex.mayExist(manager, new ResourceLocation("minecraft", "textures/blocks/shared_n.png")));
        final ZipFile jar = pack.getResourcePackZipFile();
        assertSame(jar, pack.getResourcePackZipFile());
        assertNotNull(jar.getEntry("assets/minecraft/textures/blocks/shared_n.png"));
    }
}
