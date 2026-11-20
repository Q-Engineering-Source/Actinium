package com.dhj.actinium.render.iris;

import net.coderbot.iris.gbuffer_overrides.state.RenderTargetStateListener;

/** Suspends Iris pass selection while vanilla owns the entity outline targets. */
public final class EntityOutlinePass {
    private Runnable restore;

    /** Bounds the outline pass to the enclosing entity render, including exceptional exits. */
    public void withinEntityRender(Runnable draw) {
        Runnable outer = this.restore;
        this.restore = null;
        try {
            draw.run();
        } finally {
            try {
                this.finish();
            } finally {
                this.restore = outer;
            }
        }
    }

    /** Releases the shader-pack program before vanilla first touches the outline framebuffer. */
    public void begin(RenderTargetStateListener targets, Runnable restoreWorldPass) {
        if (this.restore != null) {
            throw new IllegalStateException("Entity outline pass is already active");
        }
        this.restore = () -> {
            targets.endPostChain();
            restoreWorldPass.run();
        };
        targets.beginPostChain();
    }

    /** Rebinds the Iris pass after vanilla rebinds the Minecraft framebuffer. */
    public void finish() {
        Runnable restore = this.restore;
        if (restore != null) {
            this.restore = null;
            restore.run();
        }
    }
}
