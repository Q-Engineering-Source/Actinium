package net.coderbot.iris.texture.pbr;

import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
import net.coderbot.iris.Iris;
import net.minecraft.client.resources.AbstractResourcePack;
import net.minecraft.client.resources.FallbackResourceManager;
import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.FMLFileResourcePack;
import net.minecraftforge.fml.client.FMLFolderResourcePack;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Indexes PBR texture names in resource packs so atlas and simple texture loading can skip failed
 * lookups. Unknown pack types and every failed scan retain the original resource loading path.
 */
public final class PBRResourceIndex {
	private static final String ASSETS = "assets";
	private static final String NORMAL_NAME = PBRType.NORMAL.getSuffix() + ".png";
	private static final String SPECULAR_NAME = PBRType.SPECULAR.getSuffix() + ".png";
	private static final Object LOCK = new Object();
	private static final LongAdder lookups = new LongAdder();
	private static final LongAdder memoHits = new LongAdder();
	private static final LongAdder answeredAbsent = new LongAdder();
	private static final LongAdder passedThrough = new LongAdder();
	private static final LongAdder directPackChecks = new LongAdder();

	private static long nextGeneration;
	private static volatile IndexState current;

	private PBRResourceIndex() {
	}

	private record PackIndex(Set<String> names, boolean lowerCase) {
	}

	private record ScanTarget(IResourcePack pack, ZipFile zip, File folder) {
	}

	private static final class IndexState {
		private final IResourceManager owner;
		private final long generation;
		private final Map<ResourceLocation, Boolean> answers = new HashMap<>();
		private final AtomicBoolean failureLogged = new AtomicBoolean();
		private volatile CompletableFuture<Map<IResourcePack, PackIndex>> pending;
		private volatile Map<IResourcePack, PackIndex> built;
		private volatile boolean disabled;

		private IndexState(IResourceManager owner, long generation) {
			this.owner = owner;
			this.generation = generation;
		}
	}

	/**
	 * Starts a new index generation for a resource reload. Pack ZipFiles are obtained on the caller
	 * thread and remain owned by their resource packs; only file and ZIP enumeration runs on a worker.
	 */
	public static void start(IResourceManager resourceManager) {
		synchronized (LOCK) {
			startLocked(resourceManager);
		}
	}

	/**
	 * Returns whether a PBR location may exist. A false answer is returned only after every known pack
	 * has been scanned and every unrecognized pack has been queried through its normal resource API.
	 */
	public static boolean mayExist(IResourceManager resourceManager, ResourceLocation location) {
		if (!(resourceManager instanceof SimpleReloadableResourceManager manager)) {
			return true;
		}

		final String path = location.getPath();
		if (!path.endsWith(NORMAL_NAME) && !path.endsWith(SPECULAR_NAME)) {
			passedThrough.increment();
			return true;
		}
		lookups.increment();

		final IndexState state = stateFor(manager);
		final Map<IResourcePack, PackIndex> index = awaitIndex(state);
		if (index == null) {
			return true;
		}

		synchronized (LOCK) {
			if (current != state || state.disabled) {
				return true;
			}
			final Boolean known = state.answers.get(location);
			if (known != null) {
				memoHits.increment();
				return known;
			}
		}

		final boolean present = lookUp(manager, location, path, index);
		synchronized (LOCK) {
			if (current != state || state.disabled) {
				return true;
			}
			final Boolean known = state.answers.get(location);
			if (known != null) {
				memoHits.increment();
				return known;
			}
			state.answers.put(location, present);
			if (!present) {
				answeredAbsent.increment();
			}
			return present;
		}
	}

	/**
	 * Drops the generation between tests. Production invalidation uses {@link #start(IResourceManager)}
	 * so same-manager reloads always receive a fresh generation.
	 */
	static void drop() {
		synchronized (LOCK) {
			current = null;
			nextGeneration++;
		}
	}

	/** Returns the periodic index counters, resetting the counters after each report. */
	public static String statsSummary() {
		final long queries = lookups.sumThenReset();
		final long memo = memoHits.sumThenReset();
		final long absent = answeredAbsent.sumThenReset();
		final long passed = passedThrough.sumThenReset();
		final long packChecks = directPackChecks.sumThenReset();
		if (queries == 0L && passed == 0L && packChecks == 0L) {
			return "";
		}
		return "pbr[lookups=" + queries
			+ ",memoHits=" + memo
			+ ",answeredAbsent=" + absent
			+ ",notPbr=" + passed
			+ ",directPackChecks=" + packChecks
			+ ",state=" + stateSummary()
			+ ']';
	}

	private static IndexState stateFor(SimpleReloadableResourceManager manager) {
		synchronized (LOCK) {
			if (current == null || current.owner != manager) {
				return startLocked(manager);
			}
			return current;
		}
	}

	private static IndexState startLocked(IResourceManager resourceManager) {
		final IndexState state = new IndexState(resourceManager, ++nextGeneration);
		current = state;
		if (!(resourceManager instanceof SimpleReloadableResourceManager manager)) {
			return state;
		}

		try {
			final List<ScanTarget> targets = collectTargets(manager);
			final long scanStartNanos = System.nanoTime();
			state.pending = AngelicaWorkers.submit(() -> {
				final Map<IResourcePack, PackIndex> scanned = scan(targets);
				logScan(state.generation, scanned, System.nanoTime() - scanStartNanos);
				return scanned;
			});
			state.pending.whenComplete((scanned, failure) -> {
				if (failure != null) {
					fail(state, rootCause(failure));
				}
			});
		} catch (RuntimeException e) {
			fail(state, e);
		}
		return state;
	}

	private static Map<IResourcePack, PackIndex> awaitIndex(IndexState state) {
		if (state.disabled) {
			return null;
		}
		Map<IResourcePack, PackIndex> built = state.built;
		if (built != null) {
			return built;
		}
		final CompletableFuture<Map<IResourcePack, PackIndex>> pending = state.pending;
		if (pending == null) {
			return null;
		}

		try {
			built = pending.join();
		} catch (RuntimeException e) {
			fail(state, rootCause(e));
			return null;
		}

		synchronized (LOCK) {
			if (current != state || state.disabled) {
				return null;
			}
			if (state.built == null) {
				state.built = built;
			}
			return state.built;
		}
	}

	private static Throwable rootCause(Throwable failure) {
		while (failure instanceof CompletionException && failure.getCause() != null) {
			failure = failure.getCause();
		}
		return failure;
	}

	private static void fail(IndexState state, Throwable failure) {
		state.disabled = true;
		state.built = null;
		if (state.failureLogged.compareAndSet(false, true)) {
			Iris.logger.warn("PBR resource index generation " + state.generation + " failed; falling back to direct resource lookups", failure);
		}
	}

	private static String stateSummary() {
		synchronized (LOCK) {
			final IndexState state = current;
			if (state == null) {
				return "idle";
			}
			if (state.disabled) {
				return "disabled";
			}
			final Map<IResourcePack, PackIndex> built = state.built;
			if (built == null) {
				final CompletableFuture<Map<IResourcePack, PackIndex>> pending = state.pending;
				return pending == null ? "idle" : pending.isDone() ? "ready(unclaimed)" : "scanning";
			}
			int names = 0;
			for (PackIndex pack : built.values()) {
				names += pack.names().size();
			}
			return "ready(packs=" + built.size() + ",names=" + names + ')';
		}
	}

	private static void logScan(long generation, Map<IResourcePack, PackIndex> scanned, long nanos) {
		int names = 0;
		for (PackIndex index : scanned.values()) {
			names += index.names().size();
		}
		Iris.logger.info("PBR resource index generation {}: scanned {} packs and {} PBR names in {} ms",
			generation, scanned.size(), names, nanos / 1_000_000L);
	}

	private static boolean lookUp(SimpleReloadableResourceManager manager, ResourceLocation location, String path,
								   Map<IResourcePack, PackIndex> built) {
		final IResourceManager domain = manager.domainResourceManagers.get(location.getNamespace());
		if (domain == null) {
			return false;
		}
		if (!(domain instanceof FallbackResourceManager fallback)) {
			return true;
		}

		final String name = ASSETS + "/" + location.getNamespace() + "/" + path;
		String lower = null;
		final List<IResourcePack> packs = fallback.resourcePacks;
		for (int i = packs.size() - 1; i >= 0; i--) {
			final IResourcePack pack = packs.get(i);
			final PackIndex index = built.get(pack);
			final boolean present;
			if (index == null) {
				directPackChecks.increment();
				present = pack.resourceExists(location);
			} else if (index.lowerCase()) {
				if (lower == null) {
					lower = name.toLowerCase(Locale.ROOT);
				}
				present = index.names().contains(lower);
			} else {
				present = index.names().contains(name);
			}
			if (present) {
				return true;
			}
		}
		return false;
	}

	private static List<ScanTarget> collectTargets(SimpleReloadableResourceManager manager) {
		final Set<IResourcePack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		final List<ScanTarget> targets = new ArrayList<>();
		for (FallbackResourceManager domain : manager.domainResourceManagers.values()) {
			for (IResourcePack pack : domain.resourcePacks) {
				if (seen.add(pack)) {
					final ScanTarget target = target(pack);
					if (target != null) {
						targets.add(target);
					}
				}
			}
		}
		return targets;
	}

	private static ScanTarget target(IResourcePack pack) {
		final Class<?> type = pack.getClass();
		if (type == FileResourcePack.class || type == FMLFileResourcePack.class) {
			final FileResourcePack zipPack = (FileResourcePack) pack;
			try {
				return new ScanTarget(pack, zipPack.getResourcePackZipFile(), null);
			} catch (IOException e) {
				throw new IllegalStateException("Unable to index " + zipPack.getResourcePackFile(), e);
			}
		}
		if (type == FolderResourcePack.class || type == FMLFolderResourcePack.class) {
			return new ScanTarget(pack, null, ((AbstractResourcePack) pack).getResourcePackFile());
		}
		return null;
	}

	private static Map<IResourcePack, PackIndex> scan(List<ScanTarget> targets) {
		final Map<IResourcePack, PackIndex> result = new IdentityHashMap<>();
		for (ScanTarget target : targets) {
			result.put(target.pack(), target.zip() != null ? scanZip(target.zip()) : scanFolder(target.folder()));
		}
		return result;
	}

	private static boolean isPbrName(String name) {
		return name.endsWith(NORMAL_NAME) || name.endsWith(SPECULAR_NAME);
	}

	private static PackIndex scanZip(ZipFile zip) {
		final Set<String> names = new HashSet<>();
		final Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			final ZipEntry entry = entries.nextElement();
			final String entryName = entry.getName();
			// ZipFile.getEntry(name) may resolve a directory entry stored as "name/" for "name".
			final String key = entryName.endsWith("/") ? entryName.substring(0, entryName.length() - 1) : entryName;
			if (isPbrName(key)) {
				names.add(key);
			}
		}
		return new PackIndex(names, false);
	}

	private static PackIndex scanFolder(File file) {
		final Set<String> names = new HashSet<>();
		final Path root = file.toPath();
		final Path assets = root.resolve(ASSETS);
		final boolean caseInsensitive = Files.exists(root.resolve(ASSETS.toUpperCase(Locale.ROOT)));
		try {
			Files.walkFileTree(assets, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
					final String relative = root.relativize(path).toString().replace(File.separatorChar, '/');
					final String name = caseInsensitive ? relative.toLowerCase(Locale.ROOT) : relative;
					if (isPbrName(name)) {
						names.add(name);
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path path, IOException failure) throws IOException {
					if (failure instanceof NoSuchFileException) {
						return FileVisitResult.CONTINUE;
					}
					throw failure;
				}
			});
		} catch (NoSuchFileException e) {
			return new PackIndex(names, caseInsensitive);
		} catch (IOException e) {
			throw new IllegalStateException("Unable to index resource pack folder " + file, e);
		}
		return new PackIndex(names, caseInsensitive);
	}
}
