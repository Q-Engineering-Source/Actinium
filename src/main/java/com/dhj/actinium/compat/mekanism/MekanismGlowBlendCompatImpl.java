package com.dhj.actinium.compat.mekanism;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import net.coderbot.iris.apiimpl.IrisApiV0Impl;
import net.coderbot.iris.gl.blending.BlendModeStorage;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.Deque;

/** Applies Mekanism's deferred alpha blend only while its glow geometry is being rendered. */
final class MekanismGlowBlendCompatImpl implements MekanismGlowBlendCompat {
    private static final ThreadLocal<Deque<BlendScope>> ACTIVE_SCOPES = new ThreadLocal<>();

    @Override
    public void begin() {
        Deque<BlendScope> activeScopes = ACTIVE_SCOPES.get();
        boolean nested = activeScopes != null && !activeScopes.isEmpty();
        BlendState requestedBlend = new BlendState();
        BlendModeStorage.FUNC_LAYER.readVanilla(requestedBlend);
        boolean shaderPackInUse = IrisApiV0Impl.INSTANCE.isShaderPackInUse();
        boolean blendLocked = BlendModeStorage.isBlendLocked();
        boolean deferredBlendEnabled = BlendModeStorage.ENABLE_LAYER.getVanilla();
        boolean alphaBlendRequested = isMekanismAlphaBlend(requestedBlend);
        // The saved vanilla blend remains authoritative after BlendModeStorage's dirty flag clears.
        boolean applyOverride = shaderPackInUse
            && blendLocked
            && deferredBlendEnabled
            && alphaBlendRequested;

        if (!applyOverride) {
            if (nested) {
                activeScopes.push(BlendScope.inactive());
            }
            return;
        }

        BlendState shaderBlend = GLStateManager.getBlendState().copy();
        boolean shaderBlendEnabled = GLStateManager.getBlendMode().isEnabled();
        BlendModeStorage.overrideBlend(requestedBlend);

        if (activeScopes == null) {
            activeScopes = new ArrayDeque<>();
            ACTIVE_SCOPES.set(activeScopes);
        }
        activeScopes.push(new BlendScope(shaderBlendEnabled, shaderBlend));
    }

    @Override
    public void end() {
        Deque<BlendScope> activeScopes = ACTIVE_SCOPES.get();
        if (activeScopes == null || activeScopes.isEmpty()) {
            return;
        }

        BlendScope scope = activeScopes.pop();
        try {
            if (scope.overrideApplied) {
                BlendModeStorage.restoreBlend();
                BlendModeStorage.overrideBlend(scope.shaderBlendEnabled ? scope.shaderBlend : null);
            }
        } finally {
            if (activeScopes.isEmpty()) {
                ACTIVE_SCOPES.remove();
            }
        }
    }

    private static boolean isMekanismAlphaBlend(BlendState blend) {
        boolean usesSourceAlpha = blend.getSrcRgb() == GL11.GL_SRC_ALPHA
            && blend.getDstRgb() == GL11.GL_ONE_MINUS_SRC_ALPHA;
        boolean usesStandardAlphaFactors = blend.getSrcAlpha() == GL11.GL_SRC_ALPHA
            && blend.getDstAlpha() == GL11.GL_ONE_MINUS_SRC_ALPHA;
        boolean usesSeparateAlphaFactors = blend.getSrcAlpha() == GL11.GL_ONE
            && blend.getDstAlpha() == GL11.GL_ZERO;
        return usesSourceAlpha && (usesStandardAlphaFactors || usesSeparateAlphaFactors);
    }

    private static final class BlendScope {
        private final boolean overrideApplied;
        private final boolean shaderBlendEnabled;
        private final BlendState shaderBlend;

        private BlendScope(boolean shaderBlendEnabled, BlendState shaderBlend) {
            this.overrideApplied = true;
            this.shaderBlendEnabled = shaderBlendEnabled;
            this.shaderBlend = shaderBlend;
        }

        private BlendScope() {
            this.overrideApplied = false;
            this.shaderBlendEnabled = false;
            this.shaderBlend = null;
        }

        private static BlendScope inactive() {
            return new BlendScope();
        }
    }
}
