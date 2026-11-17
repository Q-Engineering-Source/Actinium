package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.api.util.ColorABGR;
import com.gtnewhorizons.angelica.client.rendering.ModelViewDelta;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.states.Color4;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;

/**
 * Bakes the modelview delta between capture and replay into the captured vertices.
 *
 * <p>Capture code writes vertices into the current modelview; replaying them from a display list
 * would apply whatever modelview is current then. This tessellator stores every vertex in the
 * frame captured by {@link #begin()} so the stored template can be replayed under any matrix.</p>
 */
public final class BakedTransformCapture extends DirectTessellator {

    private final ModelViewDelta delta = new ModelViewDelta();
    private final Matrix4f deltaMatrix = new Matrix4f();
    private final Vector3f scratch = new Vector3f();
    private int firstColor;
    private boolean sawRun;
    private boolean colorUniform = true;

    public BakedTransformCapture(int capacity) {
        super(memAlloc(capacity));
    }

    public void begin() {
        TessellatorManager.startCapturingDirect(this);
        delta.snapshot();
        sawRun = false;
        colorUniform = true;
    }

    public TemplateBuffer end() {
        final TemplateBuffer template = colorUniform ? TemplateCapture.toTemplate(this) : null;
        TessellatorManager.stopCapturingDirect();
        return template;
    }

    @Override
    public int draw() {
        final Color4 color = GLSMCurrentAttribs.getColor();
        final int packed = ColorABGR.pack(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
        if (!sawRun) {
            sawRun = true;
            firstColor = packed;
        } else if (packed != firstColor) {
            colorUniform = false;
        }
        return super.draw();
    }

    @Override
    public void addVertex(double x, double y, double z) {
        final Matrix4fc direct = delta.deltaOrNull(deltaMatrix);
        if (direct == null) {
            super.addVertex(x, y, z);
            return;
        }
        final double offsetX = getXOffset();
        final double offsetY = getYOffset();
        final double offsetZ = getZOffset();
        scratch.set((float) (x + offsetX), (float) (y + offsetY), (float) (z + offsetZ)).mulPosition(direct);
        super.addVertex(scratch.x - offsetX, scratch.y - offsetY, scratch.z - offsetZ);
    }

    @Override
    public void setNormal(float nx, float ny, float nz) {
        final Matrix4fc direct = delta.deltaOrNull(deltaMatrix);
        if (direct == null) {
            super.setNormal(nx, ny, nz);
            return;
        }
        scratch.set(nx, ny, nz).mulDirection(direct).normalize();
        super.setNormal(scratch.x, scratch.y, scratch.z);
    }

    @Override
    public void setPackedNormalRaw(int packed) {
        final Matrix4fc direct = delta.deltaOrNull(deltaMatrix);
        if (direct == null) {
            super.setPackedNormalRaw(packed);
            return;
        }
        setNormal((byte) (packed & 0xFF) / 127.0F, (byte) (packed >> 8 & 0xFF) / 127.0F, (byte) (packed >> 16 & 0xFF) / 127.0F);
    }
}
