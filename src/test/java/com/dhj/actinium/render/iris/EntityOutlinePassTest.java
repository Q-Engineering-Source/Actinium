package com.dhj.actinium.render.iris;

import net.coderbot.iris.gbuffer_overrides.state.RenderTargetStateListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityOutlinePassTest {
    @Test
    void vanillaOwnsTheTargetsThroughTheMaskDrawAndPostprocessing() {
        var scope = new EntityOutlinePass();
        var targets = new Targets();

        scope.withinEntityRender(() -> {
            scope.begin(targets, targets::matchWorldPass);
            targets.bound = Target.OUTLINE;
            targets.matchWorldPass();
            assertEquals(Target.OUTLINE, targets.bound);
            targets.bound = Target.OUTLINE_POST;
            targets.matchWorldPass();
            assertEquals(Target.OUTLINE_POST, targets.bound);

            targets.bound = Target.MINECRAFT;
            scope.finish();
            assertEquals(Target.IRIS_WORLD, targets.bound);
        });

        assertEquals(1, targets.worldBindings);
    }

    @Test
    void renderFailureStillRestoresWorldPassSelection() {
        var scope = new EntityOutlinePass();
        var targets = new Targets();
        var failure = new IllegalStateException("Outline renderer failed");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> scope.withinEntityRender(() -> {
            scope.begin(targets, targets::matchWorldPass);
            targets.bound = Target.OUTLINE;
            throw failure;
        })));

        assertEquals(Target.IRIS_WORLD, targets.bound);
    }

    private enum Target { IRIS_WORLD, OUTLINE, OUTLINE_POST, MINECRAFT }

    private static final class Targets implements RenderTargetStateListener {
        private Target bound = Target.IRIS_WORLD;
        private boolean postChain;
        private boolean mainBound = true;
        private int worldBindings;

        private void matchWorldPass() {
            if (this.mainBound && !this.postChain) {
                this.bound = Target.IRIS_WORLD;
                this.worldBindings++;
            }
        }

        @Override
        public void beginPostChain() {
            this.postChain = true;
        }

        @Override
        public void endPostChain() {
            this.postChain = false;
        }

        @Override
        public void setIsMainBound(boolean bound) {
            this.mainBound = bound;
        }
    }
}
