package com.dhj.actinium.debug;

import com.dhj.actinium.runtime.ActiniumRuntime;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Captures the real GL state around an external renderer's hooks and prints only the fields that changed.
 *
 * Motivation: translucent terrain rendered as a solid colour block while Distant Horizons' transparent
 * LOD pass was active; a DH build carrying its own GL state logging crashes when combined with Actinium,
 * so this is the Actinium equivalent of DH's {@code GLStateSnapshot} / {@code diffAndPrint}. It is hooked
 * twice, around DH's injection point and around Celeritas' translucent draw, so a leaked state change can
 * be attributed to one of the two.
 *
 * State is read through the LWJGL service (the driver's values) instead of glsm's tracked values: the
 * question is whether the driver and the state tracker agree. Gated by the in-game "Actinium GL debug"
 * option; {@link #capture()} returns {@code null} while it is off.
 */
public final class GlStateDiffProbe {

    private static final int QUERY_SIZE = 4;

    private GlStateDiffProbe() {
    }

    public static boolean isEnabled() {
        return ActiniumRuntime.options().debug.enableActiniumGlDebug;
    }

    /**
     * Reads the state fields reported by {@link #diffAndPrint} into an immutable snapshot.
     *
     * @return the snapshot, or {@code null} when capture is disabled
     */
    public static Snapshot capture() {
        if (!isEnabled()) {
            return null;
        }

        return new Snapshot();
    }

    /**
     * Logs the fields that differ between two snapshots.
     *
     * <p>Both arguments may be {@code null} (capture disabled), in which case nothing is logged.</p>
     *
     * @param label  identifies the measured span, e.g. {@code "dh-injection-translucent-pass1"}
     */
    public static void diffAndPrint(String label, Snapshot before, Snapshot after) {
        if (before == null || after == null) {
            return;
        }

        StringBuilder diff = new StringBuilder();
        appendDiff(diff, "blend", before.blend, after.blend);
        appendDiff(diff, "blendSrcRgb", before.blendSrcRgb, after.blendSrcRgb);
        appendDiff(diff, "blendDstRgb", before.blendDstRgb, after.blendDstRgb);
        appendDiff(diff, "blendSrcAlpha", before.blendSrcAlpha, after.blendSrcAlpha);
        appendDiff(diff, "blendDstAlpha", before.blendDstAlpha, after.blendDstAlpha);
        appendDiff(diff, "depthTest", before.depthTest, after.depthTest);
        appendDiff(diff, "depthMask", before.depthMask, after.depthMask);
        appendDiff(diff, "depthFunc", before.depthFunc, after.depthFunc);
        appendDiff(diff, "alphaTest", before.alphaTest, after.alphaTest);
        appendDiff(diff, "alphaFunc", before.alphaFunc, after.alphaFunc);
        appendDiff(diff, "cull", before.cull, after.cull);
        appendDiff(diff, "cullFace", before.cullFace, after.cullFace);
        appendDiff(diff, "colorMask", before.colorMask, after.colorMask);
        appendDiff(diff, "scissor", before.scissor, after.scissor);
        appendDiff(diff, "scissorBox", before.scissorBox, after.scissorBox);
        appendDiff(diff, "program", before.program, after.program);
        appendDiff(diff, "drawFramebuffer", before.drawFramebuffer, after.drawFramebuffer);
        appendDiff(diff, "readFramebuffer", before.readFramebuffer, after.readFramebuffer);
        appendDiff(diff, "viewport", before.viewport, after.viewport);
        appendDiff(diff, "activeTexture", before.activeTexture, after.activeTexture);
        appendDiff(diff, "texture2D", before.texture2D, after.texture2D);

        if (diff.length() == 0) {
            return;
        }

        LogManager.getLogger("ActiniumGLDiff").info("[Actinium-GLDIFF] {}: {}", label, diff);
    }

    private static void appendDiff(StringBuilder diff, String name, Object before, Object after) {
        if (before.equals(after)) {
            return;
        }

        if (diff.length() > 0) {
            diff.append(' ');
        }

        diff.append(name).append(' ').append(before).append(" -> ").append(after);
    }

    /**
     * Immutable capture of the state fields this probe compares.
     *
     * <p>Every field is read once, in the constructor, from a real GL query; the class intentionally has
     * no setters so a captured value cannot drift while the span is measured.</p>
     */
    public static final class Snapshot {
        private final boolean blend;
        private final int blendSrcRgb;
        private final int blendDstRgb;
        private final int blendSrcAlpha;
        private final int blendDstAlpha;
        private final boolean depthTest;
        private final boolean depthMask;
        private final int depthFunc;
        private final boolean alphaTest;
        private final int alphaFunc;
        private final boolean cull;
        private final int cullFace;
        private final String colorMask;
        private final boolean scissor;
        private final String scissorBox;
        private final int program;
        private final int drawFramebuffer;
        private final int readFramebuffer;
        private final String viewport;
        private final int activeTexture;
        private final int texture2D;

        private Snapshot() {
            this.blend = LWJGL.glGetBoolean(GL11.GL_BLEND);
            this.blendSrcRgb = LWJGL.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            this.blendDstRgb = LWJGL.glGetInteger(GL14.GL_BLEND_DST_RGB);
            this.blendSrcAlpha = LWJGL.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            this.blendDstAlpha = LWJGL.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            this.depthTest = LWJGL.glGetBoolean(GL11.GL_DEPTH_TEST);
            this.depthMask = LWJGL.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            this.depthFunc = LWJGL.glGetInteger(GL11.GL_DEPTH_FUNC);
            this.alphaTest = LWJGL.glGetBoolean(GL11.GL_ALPHA_TEST);
            this.alphaFunc = LWJGL.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            this.cull = LWJGL.glGetBoolean(GL11.GL_CULL_FACE);
            this.cullFace = LWJGL.glGetInteger(GL11.GL_CULL_FACE_MODE);
            this.colorMask = queryString(GL11.GL_COLOR_WRITEMASK);
            this.scissor = LWJGL.glGetBoolean(GL11.GL_SCISSOR_TEST);
            this.scissorBox = queryString(GL11.GL_SCISSOR_BOX);
            this.program = LWJGL.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            this.drawFramebuffer = LWJGL.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            this.readFramebuffer = LWJGL.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            this.viewport = queryString(GL11.GL_VIEWPORT);
            this.activeTexture = LWJGL.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            this.texture2D = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }

        /**
         * Reads a four-component integer state as text, which keeps the diff independent of how the
         * individual components are exposed.
         *
         * <p>The alpha test reference value is intentionally not captured: the LWJGL service exposes no
         * float query, and the function selector is enough to spot a leaked alpha test.</p>
         */
        private static String queryString(int pname) {
            int[] values = new int[QUERY_SIZE];
            LWJGL.glGetIntegerv(pname, values);
            return "[" + values[0] + ", " + values[1] + ", " + values[2] + ", " + values[3] + "]";
        }
    }
}
