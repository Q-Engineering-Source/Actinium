package com.dhj.actinium.render;

import java.util.function.Function;

/**
 * Restores one shader-pack pass exactly once when a scoped operation used an owned GL program.
 */
final class EndPortalActivePassScope {
    private EndPortalActivePassScope() {
    }

    /**
     * Gives the operation a marker it must invoke before switching to the compositor program.
     *
     * @param operation scoped operation receiving the program-use marker
     * @param restoreActivePass complete shader-pack pass restoration
     * @param <T> operation result type
     */
    static <T> T run(Function<Runnable, T> operation, Runnable restoreActivePass) {
        boolean[] programUsed = {false};
        try {
            return operation.apply(() -> programUsed[0] = true);
        } finally {
            if (programUsed[0]) {
                restoreActivePass.run();
            }
        }
    }
}
