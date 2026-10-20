package net.coderbot.iris.gl.program;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Linked programs kept from pipelines that were destroyed, so that turning shaders off and on, reloading
 * an unchanged pack, or travelling between two dimensions reuses the programs the previous pipeline
 * already linked instead of compiling and linking the same sources again.
 *
 * <p>A program is only reusable while its pack configuration is unchanged, which
 * {@link #useConfiguration(String)} tracks: a different configuration retires every kept program. Two
 * batches are held. Programs retained when a pipeline is destroyed form the newest batch; the batch before
 * it is retired when the next pipeline has been built, which gives a program one more build to be taken
 * before it is deleted.</p>
 *
 * <p>Ownership: a program enters the pool through {@link #retain} (the destroying wrapper hands its GL name
 * over instead of deleting it), leaves it through {@link #take} (the new wrapper owns it from then on), and
 * is deleted by the pool when it is evicted, retired or exceeded. A name therefore has exactly one owner at
 * any time: either one {@link Program}/{@link ComputeProgram} wrapper or the pool. The one exception is
 * {@link #discardAll()}, where the pool forgets names of a context that is gone instead of deleting them,
 * because on the new context those numbers may name something else.</p>
 *
 * <p>The pool is bounded twice: one {@link Sources} key keeps at most {@link #MAX_PROGRAMS_PER_SOURCES}
 * programs per batch, which covers several passes that happen to link identical sources, and both batches
 * together keep at most {@link #MAX_RETAINED_PROGRAMS} programs, which bounds the pool when a pack has many
 * dimension folders. Evicting is always a real deletion, never a leak.</p>
 */
public final class RetainedPrograms {
	/**
	 * The stage sources a linked program was built from; two programs with equal sources are interchangeable.
	 *
	 * @param vertex the vertex stage source
	 * @param geometry the geometry stage source, or null when the program has none
	 * @param tessControl the tessellation control stage source, or null when the program has none
	 * @param tessEval the tessellation evaluation stage source, or null when the program has none
	 * @param fragment the fragment stage source
	 * @param compute the compute stage source, or null for a graphics program
	 */
	public record Sources(@Nullable String vertex, @Nullable String geometry, @Nullable String tessControl,
						  @Nullable String tessEval, @Nullable String fragment, @Nullable String compute) {}

	/** Most programs one {@link Sources} key keeps per batch. */
	private static final int MAX_PROGRAMS_PER_SOURCES = 4;
	/** Most programs both batches keep together. */
	private static final int MAX_RETAINED_PROGRAMS = 512;

	/** Programs kept from the pipelines destroyed since the last {@link #afterPipelineBuilt()}. */
	private static Map<Sources, ArrayDeque<Integer>> recent = new LinkedHashMap<>();
	/** Programs kept one batch earlier: still takeable, deleted when the next pipeline has been built. */
	private static Map<Sources, ArrayDeque<Integer>> older = new LinkedHashMap<>();
	/** Number of program names the pool owns, both batches together. */
	private static int retained;
	/** Identity of the pack configuration the kept programs were linked for, or null before any pack. */
	private static String configuration;
	/** The GL call evicting and retiring use; replaced by tests through {@link #setDeleterForTesting}. */
	private static ProgramDeleter deleter = new ProgramDeleterImpl();

	private RetainedPrograms() {}

	/**
	 * Selects the pack configuration the kept programs belong to. A configuration that differs from the
	 * previous one retires every kept program, because programs linked for another pack must never be used.
	 *
	 * <p>The identity is the pack settings fingerprint supplied by {@code Iris}: the pack file, the files it
	 * contains, the options applied to it and the environment defines it was parsed under. A reload of an
	 * unchanged pack therefore keeps its programs, and a pack switch or an option change drops them.</p>
	 *
	 * @param settings identity of the configuration, or null to retire everything without selecting one
	 */
	public static void useConfiguration(@Nullable String settings) {
		if (settings == null || !settings.equals(configuration)) {
			deletePool(recent);
			deletePool(older);
		}

		configuration = settings;
	}

	/**
	 * Drops every kept program without deleting it, for the case where the GL context the names were created
	 * on is gone.
	 *
	 * <p>The names are names in the old context. On the new one the same numbers may already refer to
	 * unrelated objects, so deleting them could destroy a program that context owns; they are therefore
	 * forgotten rather than freed - losing the context lost the objects behind them. The configuration goes
	 * with them, because no kept program belongs to it any more.</p>
	 *
	 * @return the number of program names dropped, so the caller can report what the migration cost
	 */
	public static int discardAll() {
		final int discarded = retained;
		recent.clear();
		older.clear();
		retained = 0;
		configuration = null;

		return discarded;
	}

	/** Hands a program over to the pool instead of deleting it when its pipeline is destroyed. */
	static void retain(Sources sources, int program) {
		if (program == 0) {
			return;
		}

		final ArrayDeque<Integer> programs = recent.computeIfAbsent(sources, key -> new ArrayDeque<>());
		programs.addLast(program);
		retained++;

		while (programs.size() > MAX_PROGRAMS_PER_SOURCES) {
			final int evicted = programs.pollFirst();
			delete(evicted);
			retained--;
		}

		enforceCapacity();
	}

	/**
	 * Takes a kept program for these stage sources, transferring its ownership to the caller.
	 *
	 * @return the program, or 0 when nothing matching is kept. A taken program is never handed out twice.
	 */
	static int take(Sources sources) {
		final int program = pollFrom(recent, sources);
		return program != 0 ? program : pollFrom(older, sources);
	}

	/**
	 * Called once a pipeline has been built: the batch before the one the caller just built can no longer be
	 * taken by a later pipeline, so it is deleted and the newest batch takes its place.
	 */
	public static void afterPipelineBuilt() {
		deletePool(older);
		older = recent;
		recent = new LinkedHashMap<>();
	}

	/**
	 * Replaces the GL call the pool makes. Only tests use it: it lets eviction and retirement be observed
	 * without a context. Passing null restores the production implementation.
	 */
	static void setDeleterForTesting(@Nullable ProgramDeleter replacement) {
		deleter = replacement != null ? replacement : new ProgramDeleterImpl();
	}

	private static int pollFrom(Map<Sources, ArrayDeque<Integer>> pool, Sources sources) {
		final ArrayDeque<Integer> programs = pool.get(sources);
		if (programs == null) {
			return 0;
		}

		final int program = programs.pollFirst();
		if (programs.isEmpty()) {
			pool.remove(sources);
		}
		retained--;

		return program;
	}

	/**
	 * Deletes kept programs until the pool fits its bound. The older batch is the oldest kept work, so it is
	 * the first to go; the newest batch only gives programs up when the older one alone does not cover the
	 * overflow, which keeps the programs the current pipeline may ask for again.
	 */
	private static void enforceCapacity() {
		while (retained > MAX_RETAINED_PROGRAMS) {
			if (evictOldest(older)) {
				continue;
			}

			if (!evictOldest(recent)) {
				return;
			}
		}
	}

	/** Deletes and drops the oldest program of a batch. Returns false when the batch is empty. */
	private static boolean evictOldest(Map<Sources, ArrayDeque<Integer>> pool) {
		final Iterator<Map.Entry<Sources, ArrayDeque<Integer>>> entries = pool.entrySet().iterator();
		if (!entries.hasNext()) {
			return false;
		}

		final Map.Entry<Sources, ArrayDeque<Integer>> oldest = entries.next();
		final int program = oldest.getValue().pollFirst();
		if (oldest.getValue().isEmpty()) {
			entries.remove();
		}

		delete(program);
		retained--;

		return true;
	}

	/** Deletes every program of a batch and empties it. */
	private static void deletePool(Map<Sources, ArrayDeque<Integer>> pool) {
		for (ArrayDeque<Integer> programs : pool.values()) {
			for (int program : programs) {
				delete(program);
				retained--;
			}
		}

		pool.clear();
	}

	private static void delete(int program) {
		deleter.deleteProgram(program);
	}
}
