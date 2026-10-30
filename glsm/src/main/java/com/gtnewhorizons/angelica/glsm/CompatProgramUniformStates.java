package com.gtnewhorizons.angelica.glsm;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;

/**
 * Stores compat uniform state by linked OpenGL program identifier.
 *
 * <p>Program locations are valid for every shared GL context, while upload generations are only mutated by the
 * cache-owning context. Lifecycle tokens prevent a completed lookup from publishing locations after the program has
 * been relinked or deleted on another shared context.</p>
 */
final class CompatProgramUniformStates {

    /** State entries keyed by their OpenGL program identifier. */
    private final Int2ObjectOpenHashMap<CompatProgramUniformState> states = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap lifecycleEpochs = new Int2LongOpenHashMap();
    private long nextLifecycleEpoch;

    /**
     * Removes all cached locations and upload generations for a program before linking or deleting it.
     */
    synchronized long invalidate(int program) {
        CompatProgramUniformState state = states.remove(program);
        if (state != null) {
            state.invalidate();
        }

        long lifecycleEpoch = ++nextLifecycleEpoch;
        lifecycleEpochs.put(program, lifecycleEpoch);
        return lifecycleEpoch;
    }

    /**
     * Stores locations from a successful link when the program exposes at least one compat uniform and has not been
     * invalidated since its locations were queried.
     *
     * @param lifecycleEpoch returned before the link began
     * @param locations in {@link CompatUniformManager} index order
     */
    synchronized boolean storeLinkedProgram(int program, long lifecycleEpoch, int[] locations) {
        if (lifecycleEpochs.getOrDefault(program, 0L) != lifecycleEpoch) {
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
            states.put(program, new CompatProgramUniformState(locations));
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
        return states.get(program);
    }

    /**
     * Reports whether a linked program currently has cached compat locations.
     */
    synchronized boolean contains(int program) {
        return states.containsKey(program);
    }
}
