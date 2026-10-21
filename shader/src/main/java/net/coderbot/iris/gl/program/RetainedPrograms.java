package net.coderbot.iris.gl.program;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;
import net.coderbot.iris.gl.ResourceCleanup;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Retains linked programs for reuse by a later pipeline in the same GL share group. */
public final class RetainedPrograms {
	/** The stage sources a linked program was built from. */
	public record Sources(@Nullable String vertex, @Nullable String geometry, @Nullable String tessControl,
						  @Nullable String tessEval, @Nullable String fragment, @Nullable String compute) {}

	private record ProgramEntry(int program, long order) {}
	private record Eviction(long shareGroupId, Sources sources, boolean older, ProgramEntry entry) {}

	private static final int MAX_PROGRAMS_PER_SOURCES = 4;
	private static final int MAX_RETAINED_PROGRAMS = 512;
	private static final Map<Long, GroupPool> pools = new LinkedHashMap<>();
	/** Names awaiting a live context in their owner share group before they can be deleted. */
	private static final Map<Long, ArrayDeque<Integer>> pendingDeletes = new LinkedHashMap<>();
	private static ProgramContextProvider contextProvider = new ProgramContextProviderImpl();
	private static ProgramDeleter deleter = new ProgramDeleterImpl();
	private static String configuration;
	private static long configurationEpoch;
	private static long nextProgramOrder;
	private static int cachedPrograms;
	private static int pendingProgramDeletes;

	@SuppressWarnings("unused")
	private static final AutoCloseable RETIREMENT_SUBSCRIPTION =
		GLContextRegistryProvider.addRetirementListener(RetainedPrograms::onContextRetired);

	private RetainedPrograms() {}

	/** Selects the shader-pack state whose linked programs are eligible for reuse. */
	public static synchronized void useConfiguration(@Nullable String settings) {
		if (settings != null && settings.equals(configuration)) {
			return;
		}

		configuration = settings;
		configurationEpoch++;

		final List<Runnable> cleanupActions = new ArrayList<>();
		for (Map.Entry<Long, GroupPool> groupEntry : pools.entrySet()) {
			final long shareGroupId = groupEntry.getKey();
			final GroupPool pool = groupEntry.getValue();
			appendPoolReleaseActions(cleanupActions, shareGroupId, pool.recent);
			appendPoolReleaseActions(cleanupActions, shareGroupId, pool.older);
		}
		pools.clear();
		cachedPrograms = 0;
		pruneRetiredShareGroups();
		ResourceCleanup.runAll(null, cleanupActions);
	}

	/** Forgets every retained name for controlled teardown or test cleanup. */
	static synchronized int discardAll() {
		final int discarded = cachedPrograms + pendingProgramDeletes;
		pools.clear();
		pendingDeletes.clear();
		cachedPrograms = 0;
		pendingProgramDeletes = 0;
		configuration = null;
		configurationEpoch++;
		return discarded;
	}

	/** Promotes the current share group's recent batch and retires its previous promoted batch. */
	public static synchronized void afterPipelineBuilt() {
		final long shareGroupId = currentShareGroupId();
		pruneRetiredShareGroups();
		drainPendingDeletes(shareGroupId);
		final GroupPool pool = pools.get(shareGroupId);
		if (pool == null) {
			return;
		}

		final List<Runnable> cleanupActions = new ArrayList<>();
		appendPoolReleaseActions(cleanupActions, shareGroupId, pool.older);
		final int retiredCount = count(pool.older);
		cachedPrograms -= retiredCount;
		pool.cachedPrograms -= retiredCount;
		pool.older.clear();

		pool.older = pool.recent;
		pool.recent = new LinkedHashMap<>();
		ResourceCleanup.runAll(null, cleanupActions);
		removeEmptyPool(shareGroupId, pool);
	}

	/** Reconciles retained names with context lifetimes after the display switches GL contexts. */
	public static synchronized void onDisplayContextChanged() {
		pruneRetiredShareGroups();

		final long currentShareGroupId = contextProvider.currentShareGroupId();
		if (currentShareGroupId > 0L && contextProvider.hasLiveContexts(currentShareGroupId)) {
			drainPendingDeletes(currentShareGroupId);
		}
	}

	static synchronized void setDeleterForTesting(@Nullable ProgramDeleter replacement) {
		deleter = replacement != null ? replacement : new ProgramDeleterImpl();
	}

	static synchronized void setContextProviderForTesting(@Nullable ProgramContextProvider replacement) {
		contextProvider = replacement != null ? replacement : new ProgramContextProviderImpl();
	}

	static synchronized long currentShareGroupId() {
		final long shareGroupId = contextProvider.currentShareGroupId();
		if (shareGroupId <= 0L) {
			throw new IllegalStateException("Program ownership requires a current GL share group");
		}
		return shareGroupId;
	}

	static synchronized long configurationEpoch() {
		return configurationEpoch;
	}

	static synchronized void requireCurrentShareGroup(long ownerShareGroupId) {
		final long currentShareGroupId = currentShareGroupId();
		if (currentShareGroupId != ownerShareGroupId) {
			throw new IllegalStateException("A linked program can only be used in its owner share group");
		}
	}

	static synchronized int take(Sources sources) {
		return take(currentShareGroupId(), sources);
	}

	static synchronized int take(long shareGroupId, Sources sources) {
		pruneRetiredShareGroups();
		if (contextProvider.currentShareGroupId() != shareGroupId) {
			throw new IllegalStateException("A linked program can only be taken from its owner share group");
		}
		if (!contextProvider.hasLiveContexts(shareGroupId)) {
			return 0;
		}
		if (contextProvider.currentShareGroupId() == shareGroupId) {
			drainPendingDeletes(shareGroupId);
		}

		final GroupPool pool = pools.get(shareGroupId);
		if (pool == null) {
			return 0;
		}

		final ProgramEntry program = pollFrom(pool.recent, sources);
		final ProgramEntry selected = program != null ? program : pollFrom(pool.older, sources);
		if (selected == null) {
			return 0;
		}

		pool.cachedPrograms--;
		cachedPrograms--;
		removeEmptyPool(shareGroupId, pool);
		return selected.program();
	}

	/** Compatibility entry point for an owner that is currently in the program's share group. */
	static synchronized void retain(Sources sources, int program) {
		retain(currentShareGroupId(), sources, program, configurationEpoch);
	}

	static synchronized void retain(long shareGroupId, Sources sources, int program, long ownerConfigurationEpoch) {
		if (program == 0) {
			return;
		}
		pruneRetiredShareGroups();
		if (ownerConfigurationEpoch != configurationEpoch || !contextProvider.hasLiveContexts(shareGroupId)) {
			deleteProgram(shareGroupId, program);
			return;
		}

		final GroupPool pool = pools.computeIfAbsent(shareGroupId, ignored -> new GroupPool());
		final ArrayDeque<ProgramEntry> programs = pool.recent.computeIfAbsent(sources, ignored -> new ArrayDeque<>());
		programs.addLast(new ProgramEntry(program, nextProgramOrder++));
		pool.cachedPrograms++;
		cachedPrograms++;

		final List<Runnable> cleanupActions = new ArrayList<>();
		while (programs.size() > MAX_PROGRAMS_PER_SOURCES) {
			final ProgramEntry evicted = programs.removeFirst();
			pool.cachedPrograms--;
			cachedPrograms--;
			cleanupActions.add(() -> deleteProgram(shareGroupId, evicted.program()));
		}
		appendCapacityEvictions(cleanupActions);
		ResourceCleanup.runAll(null, cleanupActions);
	}

	/** Deletes in the owner group, queues for a live remote group, or forgets a retired group's name. */
	static synchronized void deleteProgram(long shareGroupId, int program) {
		if (program == 0 || !contextProvider.hasLiveContexts(shareGroupId)) {
			return;
		}
		if (contextProvider.currentShareGroupId() == shareGroupId) {
			deleter.deleteProgram(program);
			return;
		}

		pendingDeletes.computeIfAbsent(shareGroupId, ignored -> new ArrayDeque<>()).addLast(program);
		pendingProgramDeletes++;
	}

	/** Forgets a share group's names after its final native context has been destroyed. */
	static synchronized void onContextRetired(GLContextIdentity identity) {
		final long shareGroupId = identity.shareGroupId();
		if (!contextProvider.hasLiveContexts(shareGroupId)) {
			dropShareGroup(shareGroupId);
		}
	}

	private static void appendCapacityEvictions(List<Runnable> cleanupActions) {
		while (cachedPrograms > MAX_RETAINED_PROGRAMS) {
			final Eviction oldest = oldestProgram();
			if (oldest == null) {
				return;
			}

			final GroupPool pool = pools.get(oldest.shareGroupId());
			final Map<Sources, ArrayDeque<ProgramEntry>> batch = oldest.older() ? pool.older : pool.recent;
			final ArrayDeque<ProgramEntry> programs = batch.get(oldest.sources());
			programs.removeFirst();
			if (programs.isEmpty()) {
				batch.remove(oldest.sources());
			}
			pool.cachedPrograms--;
			cachedPrograms--;
			cleanupActions.add(() -> deleteProgram(oldest.shareGroupId(), oldest.entry().program()));
			removeEmptyPool(oldest.shareGroupId(), pool);
		}
	}

	private static Eviction oldestProgram() {
		Eviction oldest = null;
		for (Map.Entry<Long, GroupPool> groupEntry : pools.entrySet()) {
			final long shareGroupId = groupEntry.getKey();
			final GroupPool pool = groupEntry.getValue();
			oldest = oldestInBatch(shareGroupId, pool.recent, false, oldest);
			oldest = oldestInBatch(shareGroupId, pool.older, true, oldest);
		}
		return oldest;
	}

	private static Eviction oldestInBatch(long shareGroupId, Map<Sources, ArrayDeque<ProgramEntry>> batch,
									  boolean older, @Nullable Eviction oldest) {
		for (Map.Entry<Sources, ArrayDeque<ProgramEntry>> entry : batch.entrySet()) {
			final ProgramEntry candidate = entry.getValue().peekFirst();
			if (candidate != null && (oldest == null || candidate.order() < oldest.entry().order())) {
				oldest = new Eviction(shareGroupId, entry.getKey(), older, candidate);
			}
		}
		return oldest;
	}

	private static void drainPendingDeletes(long shareGroupId) {
		final ArrayDeque<Integer> pending = pendingDeletes.remove(shareGroupId);
		if (pending == null) {
			return;
		}
		pendingProgramDeletes -= pending.size();
		final List<Runnable> cleanupActions = new ArrayList<>(pending.size());
		for (int program : pending) {
			cleanupActions.add(() -> deleter.deleteProgram(program));
		}
		ResourceCleanup.runAll(null, cleanupActions);
	}

	private static void appendPoolReleaseActions(List<Runnable> actions, long shareGroupId,
										 Map<Sources, ArrayDeque<ProgramEntry>> batch) {
		for (ArrayDeque<ProgramEntry> programs : batch.values()) {
			for (ProgramEntry program : programs) {
				actions.add(() -> deleteProgram(shareGroupId, program.program()));
			}
		}
	}

	private static ProgramEntry pollFrom(Map<Sources, ArrayDeque<ProgramEntry>> pool, Sources sources) {
		final ArrayDeque<ProgramEntry> programs = pool.get(sources);
		if (programs == null) {
			return null;
		}

		final ProgramEntry program = programs.pollFirst();
		if (programs.isEmpty()) {
			pool.remove(sources);
		}
		return program;
	}

	private static int count(Map<Sources, ArrayDeque<ProgramEntry>> pool) {
		int count = 0;
		for (ArrayDeque<ProgramEntry> programs : pool.values()) {
			count += programs.size();
		}
		return count;
	}

	private static void pruneRetiredShareGroups() {
		final List<Long> groups = new ArrayList<>(pools.keySet());
		for (Long shareGroupId : pendingDeletes.keySet()) {
			if (!groups.contains(shareGroupId)) {
				groups.add(shareGroupId);
			}
		}
		for (long shareGroupId : groups) {
			if (!contextProvider.hasLiveContexts(shareGroupId)) {
				dropShareGroup(shareGroupId);
			}
		}
	}

	private static void dropShareGroup(long shareGroupId) {
		final GroupPool pool = pools.remove(shareGroupId);
		if (pool != null) {
			cachedPrograms -= pool.cachedPrograms;
		}
		final ArrayDeque<Integer> pending = pendingDeletes.remove(shareGroupId);
		if (pending != null) {
			pendingProgramDeletes -= pending.size();
		}
	}

	private static void removeEmptyPool(long shareGroupId, GroupPool pool) {
		if (pool.cachedPrograms == 0) {
			pools.remove(shareGroupId, pool);
		}
	}

	private static final class GroupPool {
		private Map<Sources, ArrayDeque<ProgramEntry>> recent = new LinkedHashMap<>();
		private Map<Sources, ArrayDeque<ProgramEntry>> older = new LinkedHashMap<>();
		private int cachedPrograms;
	}
}
