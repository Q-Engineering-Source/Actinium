package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Stores compat uniform state by linked OpenGL program identifier.
 *
 * <p>Program locations are valid for every shared GL context, while upload generations are only mutated by the
 * cache-owning context. Lifecycle tokens prevent a completed lookup from publishing locations after the program has
 * been relinked or deleted on another shared context.</p>
 */
final class CompatProgramUniformStates {

    /** State entries keyed by the share-group program namespace and its OpenGL name. */
    private final Map<ProgramKey, CompatProgramUniformState> states = new HashMap<>();
    private final Map<ProgramKey, Long> lifecycleEpochs = new HashMap<>();
    private long nextLifecycleEpoch;

    /** Keeps standalone state-machine tests on an isolated synthetic share group. */
    synchronized long invalidate(int program) {
        return invalidate(0L, program);
    }

    /**
     * Removes all cached locations and upload generations for a program before linking or deleting it.
     */
    synchronized long invalidate(long shareGroupId, int program) {
        final ProgramKey key = new ProgramKey(shareGroupId, program);
        CompatProgramUniformState state = states.remove(key);
        if (state != null) {
            state.invalidate();
        }

        long lifecycleEpoch = ++nextLifecycleEpoch;
        lifecycleEpochs.put(key, lifecycleEpoch);
        return lifecycleEpoch;
    }

    /** Keeps standalone state-machine tests on an isolated synthetic share group. */
    synchronized boolean storeLinkedProgram(int program, long lifecycleEpoch, int[] locations) {
        return storeLinkedProgram(0L, program, lifecycleEpoch, locations);
    }

    /**
     * Stores locations from a successful link when the program exposes at least one compat uniform and has not been
     * invalidated since its locations were queried.
     *
     * @param lifecycleEpoch returned before the link began
     * @param locations in {@link CompatUniformManager} index order
     */
    synchronized boolean storeLinkedProgram(long shareGroupId, int program, long lifecycleEpoch, int[] locations) {
        final ProgramKey key = new ProgramKey(shareGroupId, program);
        if (lifecycleEpochs.getOrDefault(key, 0L) != lifecycleEpoch) {
            return false;
        }

        boolean hasAny = false;
        for (int location : locations) {
            if (location != -1) {
                hasAny = true;
                break;
            }
        }

        if (hasAny) {
            states.put(key, new CompatProgramUniformState(locations));
            return true;
        }

        return false;
    }

    /**
     * Gets the location and upload state for a program.
     *
     * @return {@code null} when the program has no compat uniforms
     */
    synchronized CompatProgramUniformState get(int program) {
        return get(0L, program);
    }

    synchronized CompatProgramUniformState get(long shareGroupId, int program) {
        return states.get(new ProgramKey(shareGroupId, program));
    }

    /**
     * Reports whether a linked program currently has cached compat locations.
     */
    synchronized boolean contains(int program) {
        return contains(0L, program);
    }

    synchronized boolean contains(long shareGroupId, int program) {
        return states.containsKey(new ProgramKey(shareGroupId, program));
    }

    synchronized void retireContext(GLContextIdentity identity) {
        for (CompatProgramUniformState state : states.values()) {
            state.retireContext(identity);
        }
    }

    synchronized void retireShareGroup(long shareGroupId) {
        final Iterator<ProgramKey> stateKeys = states.keySet().iterator();
        while (stateKeys.hasNext()) {
            if (stateKeys.next().shareGroupId() == shareGroupId) {
                stateKeys.remove();
            }
        }
        lifecycleEpochs.keySet().removeIf(key -> key.shareGroupId() == shareGroupId);
    }

    private record ProgramKey(long shareGroupId, int program) {}
}
