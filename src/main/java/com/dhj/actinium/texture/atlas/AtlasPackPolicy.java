package com.dhj.actinium.texture.atlas;

import com.dhj.actinium.runtime.ActiniumRuntime;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import net.minecraft.client.resources.DefaultResourcePack;
import net.minecraft.client.resources.FallbackResourceManager;
import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which resource packs parallel atlas loading may read from worker threads.
 *
 * <p>Reading a pack off the main thread is only safe when its lookups do not touch shared mutable state, and
 * that cannot be told from the outside. Packs whose implementation ships with the game are trusted by class,
 * everything else has to be trusted by the player: untrusted pack classes make their whole domain load
 * serially, and are recorded so the atlas pack screen can offer them for trust.
 *
 * <p>Upstream also carries two hand-audited class lists and a third "claim checked" tier for packs whose
 * lookups are only weak-verified. Actinium does not carry the audited GTNH class names over to 1.12.2 without
 * an audit of this branch, and the weak tier needs exactly such a curated list, so both are absent here and
 * the player's own trust list is the only source.
 */
public final class AtlasPackPolicy {

    private static final Logger LOGGER = LogManager.getLogger("Actinium");

    private static final ResourceLocation PREWARM = new ResourceLocation("actinium", "atlas_prefetch_prewarm");
    private static final Set<String> LOGGED_UNTRUSTED = ConcurrentHashMap.newKeySet();

    private enum Kind {
        PLAIN, ZIP, AUDITED
    }

    private static final Map<Class<?>, Kind> BASE_KINDS = buildBaseKinds();

    private static Map<Class<?>, Kind> buildBaseKinds() {
        final Map<Class<?>, Kind> map = new HashMap<>();
        map.put(DefaultResourcePack.class, Kind.PLAIN);
        map.put(FolderResourcePack.class, Kind.PLAIN);
        map.put(FileResourcePack.class, Kind.ZIP);
        return map;
    }

    private AtlasPackPolicy() {
    }

    /**
     * The domains whose packs may all be read off the main thread, or an empty set when none may be.
     * Untrusted pack classes are recorded so the pack screen can list them.
     */
    public static Set<String> eligibleDomains(SimpleReloadableResourceManager manager) {
        final Set<String> trusted = new HashSet<>(Arrays.asList(options().performance.atlasTrustedPacks));
        final Set<String> distrusted = new HashSet<>(Arrays.asList(options().performance.atlasDistrustedPacks));
        final Set<String> eligible = new HashSet<>();
        final Map<String, List<String>> untrustedDomains = new HashMap<>();

        for (Map.Entry<String, FallbackResourceManager> entry : manager.domainResourceManagers.entrySet()) {
            if (checkDomain(entry.getKey(), entry.getValue(), trusted, distrusted, untrustedDomains)) {
                eligible.add(entry.getKey());
            }
        }

        recordUntrusted(untrustedDomains);
        return eligible;
    }

    /** Package-visible so the trust gate can be exercised without a loaded client. */
    static boolean checkDomain(String domainName, FallbackResourceManager domain, Set<String> trusted,
        Set<String> distrusted, Map<String, List<String>> untrustedDomains) {
        if (domain == null) {
            return false;
        }

        for (IResourcePack pack : domain.resourcePacks) {
            if (pack == null) {
                return false;
            }

            final String className = pack.getClass().getName();
            if (distrusted.contains(className)) {
                return false;
            }

            Kind kind = BASE_KINDS.get(pack.getClass());
            if (kind == null) {
                if (!trusted.contains(className)) {
                    untrustedDomains.computeIfAbsent(className, key -> new ArrayList<>(2)).add(domainName);
                    return false;
                }
                kind = Kind.AUDITED;
            }

            if (!probe(kind, pack, domainName)) {
                return false;
            }
        }

        return true;
    }

    private static boolean probe(Kind kind, IResourcePack pack, String domainName) {
        switch (kind) {
            case ZIP -> {
                try {
                    ((FileResourcePack) pack).getResourcePackZipFile();
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Resource pack {} of domain {} cannot be read off the main thread: its zip is not usable", pack.getClass().getName(), domainName, e);
                    return false;
                }
            }
            case AUDITED, PLAIN -> {
                try {
                    pack.resourceExists(PREWARM);
                } catch (RuntimeException e) {
                    LOGGER.warn("Resource pack {} of domain {} cannot be read off the main thread: its lookup failed", pack.getClass().getName(), domainName, e);
                    return false;
                }
            }
        }
        return true;
    }

    private static void recordUntrusted(Map<String, List<String>> untrustedDomains) {
        if (untrustedDomains.isEmpty()) {
            return;
        }

        final SodiumGameOptions options = options();
        boolean changed = false;
        final TreeSet<String> observed = new TreeSet<>(Arrays.asList(options.performance.atlasObservedPacks));
        for (Map.Entry<String, List<String>> entry : untrustedDomains.entrySet()) {
            final String className = entry.getKey();
            if (LOGGED_UNTRUSTED.add(className)) {
                LOGGER.info("Resource pack {} is not trusted for parallel texture loading; domains {} load serially. Trust it from Video Settings > Performance > Texture loading.", className, entry.getValue());
            }
            if (observed.add(className)) {
                changed = true;
            }
        }
        if (changed) {
            options.performance.atlasObservedPacks = observed.toArray(String[]::new);
        }
    }

    public static boolean isTrusted(String className) {
        if (Arrays.asList(options().performance.atlasDistrustedPacks).contains(className)) {
            return false;
        }
        return Arrays.asList(options().performance.atlasTrustedPacks).contains(className);
    }

    public static void setTrusted(String className, boolean trusted) {
        final SodiumGameOptions options = options();
        if (trusted) {
            options.performance.atlasTrustedPacks = withAdded(options.performance.atlasTrustedPacks, className);
            options.performance.atlasDistrustedPacks = withRemoved(options.performance.atlasDistrustedPacks, className);
        } else {
            options.performance.atlasTrustedPacks = withRemoved(options.performance.atlasTrustedPacks, className);
            options.performance.atlasDistrustedPacks = withAdded(options.performance.atlasDistrustedPacks, className);
        }
    }

    /** Every pack class the player has seen or decided on, which is the list the atlas pack screen offers. */
    public static List<String> knownPackClasses() {
        final SodiumGameOptions options = options();
        final TreeSet<String> all = new TreeSet<>();
        all.addAll(Arrays.asList(options.performance.atlasTrustedPacks));
        all.addAll(Arrays.asList(options.performance.atlasDistrustedPacks));
        all.addAll(Arrays.asList(options.performance.atlasObservedPacks));
        return new ArrayList<>(all);
    }

    private static String[] withAdded(String[] array, String value) {
        final TreeSet<String> set = new TreeSet<>(Arrays.asList(array));
        set.add(value);
        return set.toArray(String[]::new);
    }

    private static String[] withRemoved(String[] array, String value) {
        final TreeSet<String> set = new TreeSet<>(Arrays.asList(array));
        set.remove(value);
        return set.toArray(String[]::new);
    }

    private static SodiumGameOptions options() {
        return ActiniumRuntime.options();
    }
}
