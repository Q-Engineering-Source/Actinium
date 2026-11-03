package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.GLContextState;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.CowDispatch;
import com.gtnewhorizons.angelica.glsm.stacks.CowStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.RetainedState;
import java.util.List;
import org.lwjgl.opengl.GL15;
import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.glsm.GLStateManager.CLIENT_ATTRIB_STACK_DEPTH;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.ctx;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.popState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.pushState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.restorePixelStore;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.warnOnce;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.RETAINED_SLOT;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.applyHintState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.applyPointState;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.applyPolygonOffset;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.applyRetainedChanges;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.cullFace;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.glUseProgram;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.issueBlendFunc;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.lineWidthMax;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.lineWidthMin;
import static com.gtnewhorizons.angelica.glsm.GLStateManager.programLifetimeGeneration;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class GLSMAttribStack {

    private GLSMAttribStack() {}

    public static void glPushClientAttrib(int mask) {
        final int slot = ctx().clientAttribStackPointer;
        if (slot >= CLIENT_ATTRIB_STACK_DEPTH) {
            warnOnce("pushclientattrib-overflow", "glPushClientAttrib called with a full client attribute stack ({} deep); ignoring", CLIENT_ATTRIB_STACK_DEPTH);
            return;
        }
        ctx().vaos.saveClientArrays(slot, mask, ctx().boundVAO, ctx().boundVBO);
        ctx().clientAttribSavedTextureUnit[slot] = ctx().clientActiveTextureUnit;
        ctx().clientAttribSavedVertexFlags[slot] = ShaderManager.getInstance().getCurrentVertexFlags();
        if ((mask & GL11.GL_CLIENT_PIXEL_STORE_BIT) != 0) {
            ctx().clientAttribSavedPixelUnpack[slot] = ctx().pixelUnpackState;
            ctx().clientAttribSavedPixelPack[slot] = ctx().pixelPackState;
        }
        ctx().clientAttribStackPointer = slot + 1;
    }

    public static void glPopClientAttrib() {
        if (ctx().clientAttribStackPointer == 0) {
            warnOnce("popclientattrib-underflow", "glPopClientAttrib called with an empty client attribute stack; ignoring");
            return;
        }
        final int slot = --ctx().clientAttribStackPointer;
        final int mask = ctx().vaos.savedClientAttribMask(slot);
        if ((mask & GL11.GL_CLIENT_PIXEL_STORE_BIT) != 0) {
            restorePixelStore(ctx().clientAttribSavedPixelUnpack[slot], ctx().clientAttribSavedPixelPack[slot]);
            ctx().clientAttribSavedPixelUnpack[slot] = null;
            ctx().clientAttribSavedPixelPack[slot] = null;
        }
        if ((mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) == 0) return;
        ctx().vaos.restoreClientArrays(slot);
        ctx().clientActiveTextureUnit = ctx().clientAttribSavedTextureUnit[slot];
        ShaderManager.getInstance().setCurrentVertexFlags(ctx().clientAttribSavedVertexFlags[slot]);
    }

    public static void glPushAttrib(int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPushAttrib(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        GLDebug.pushGroup("pushState");
        GLStateManager.pushState(mask);
    }

    public static void glPopAttrib() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPopAttrib();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        GLDebug.popGroup();
        ctx().poppingAttributes = true;
        GLDebug.pushGroup("popState");
        try {
            popState();
        } finally {
            GLDebug.popGroup();
            ctx().poppingAttributes = false;
        }
    }

    public static void glPushAttrib() {
        glPushAttrib(GL11.GL_ENABLE_BIT);
    }

    public static void registerModifiedState(int id) {
            final GLContextState glCtx = ctx();
            if (glCtx.attribDepth > 0) {
                final int depth = glCtx.attribDepth - 1;
                glCtx.modifiedIds[depth][glCtx.modifiedCount[depth]++] = id;
            }
        }

    public static void retainModifiedState(int depth, RetainedState out) {
            final GLContextState glCtx = ctx();
            if (depth < 0 || depth != glCtx.attribDepth - 1) {
                throw new IllegalStateException("retainModifiedState: depth " + depth + " is not the open push (attrib depth " + glCtx.attribDepth + ")");
            }
            glCtx.retainedOwner = out;
            final int count = glCtx.modifiedCount[depth];
            final int[] ids = glCtx.modifiedIds[depth];
            final CowStateStack<?>[] stackById = glCtx.stackById;
            out.ensureCapacity(count);
            for (int i = 0; i < count; i++) {
                final int id = ids[i];
                stackById[id].captureSlot(RETAINED_SLOT);
                out.ids[i] = id;
            }
            out.count = count;
            out.program = glCtx.activeProgram;
            out.programGen = programLifetimeGeneration;
        }

    public static void applyRetainedState(RetainedState in) {
            final GLContextState glCtx = ctx();
            if (glCtx.retainedOwner != in) return;
            final CowStateStack<?>[] stackById = glCtx.stackById;
            final byte[] kindById = glCtx.kindById;
            final int[] ids = in.ids;
            final int count = in.count;
            int first = 0;
            while (first < count && !CowDispatch.slotChanged(kindById[ids[first]], stackById[ids[first]], RETAINED_SLOT)) first++;
            if (first < count) applyRetainedChanges(glCtx, in, first);
            if (in.program != glCtx.activeProgram && (in.program == 0 || in.programGen == programLifetimeGeneration)) {
                glUseProgram(in.program);
            }
        }

    public static void replayStateToBackend() {
            final GLContextState glCtx = ctx();

            final List<BooleanStateStack> booleanStates = glCtx.allBooleanStates;
            for (int i = 0; i < booleanStates.size(); i++) {
                booleanStates.get(i).applyToBackend();
            }

            RENDER_BACKEND.bindVertexArray(glCtx.boundVAO != 0 ? glCtx.boundVAO : glCtx.defaultVAO);
            RENDER_BACKEND.bindBuffer(GL15.GL_ARRAY_BUFFER, glCtx.boundVBO);
            RENDER_BACKEND.useProgram(glCtx.activeProgram);
            glCtx.ffp.invalidateProgram();

            RENDER_BACKEND.depthFunc(glCtx.depthState.getFunc());
            RENDER_BACKEND.depthMask(glCtx.depthState.isMaskEnabled());
            RENDER_BACKEND.clearDepth(glCtx.depthState.getClearValue());

            GLStateManager.issueBlendFunc(glCtx.blendState);
            RENDER_BACKEND.blendEquationSeparate(glCtx.blendState.getEquationRgb(), glCtx.blendState.getEquationAlpha());
            RENDER_BACKEND.blendColor(glCtx.blendState.getBlendColorR(), glCtx.blendState.getBlendColorG(), glCtx.blendState.getBlendColorB(), glCtx.blendState.getBlendColorA());
            RENDER_BACKEND.colorMask(glCtx.colorMask.red, glCtx.colorMask.green, glCtx.colorMask.blue, glCtx.colorMask.alpha);
            RENDER_BACKEND.clearColor(glCtx.clearColor.getRed(), glCtx.clearColor.getGreen(), glCtx.clearColor.getBlue(), glCtx.clearColor.getAlpha());
            RENDER_BACKEND.logicOp(glCtx.logicOpMode.getValue());

            RENDER_BACKEND.stencilFuncSeparate(GL11.GL_FRONT, glCtx.stencilState.getFuncFront(), glCtx.stencilState.getRefFront(), glCtx.stencilState.getValueMaskFront());
            RENDER_BACKEND.stencilFuncSeparate(GL11.GL_BACK, glCtx.stencilState.getFuncBack(), glCtx.stencilState.getRefBack(), glCtx.stencilState.getValueMaskBack());
            RENDER_BACKEND.stencilOpSeparate(GL11.GL_FRONT, glCtx.stencilState.getFailOpFront(), glCtx.stencilState.getZFailOpFront(), glCtx.stencilState.getZPassOpFront());
            RENDER_BACKEND.stencilOpSeparate(GL11.GL_BACK, glCtx.stencilState.getFailOpBack(), glCtx.stencilState.getZFailOpBack(), glCtx.stencilState.getZPassOpBack());
            RENDER_BACKEND.stencilMaskSeparate(GL11.GL_FRONT, glCtx.stencilState.getWriteMaskFront());
            RENDER_BACKEND.stencilMaskSeparate(GL11.GL_BACK, glCtx.stencilState.getWriteMaskBack());
            RENDER_BACKEND.clearStencil(glCtx.stencilState.getClearValue());

            RENDER_BACKEND.viewport(glCtx.viewportState.x, glCtx.viewportState.y, glCtx.viewportState.width, glCtx.viewportState.height);
            RENDER_BACKEND.depthRange(glCtx.viewportState.depthRangeNear, glCtx.viewportState.depthRangeFar);
            RENDER_BACKEND.scissor(glCtx.scissorState.x, glCtx.scissorState.y, glCtx.scissorState.width, glCtx.scissorState.height);
            RENDER_BACKEND.lineWidth(Math.max(lineWidthMin, Math.min(lineWidthMax, glCtx.lineState.getWidth())));
            applyPointState(glCtx);
            applyHintState(glCtx);

            RENDER_BACKEND.polygonMode(GL11.GL_FRONT_AND_BACK, glCtx.polygonState.getFrontMode());
            applyPolygonOffset(glCtx);
            RENDER_BACKEND.cullFace(glCtx.polygonState.getCullFaceMode());
            RENDER_BACKEND.frontFace(glCtx.polygonState.getFrontFace());
        }

    public static boolean isPoppingAttributes() {
            return ctx().poppingAttributes;
        }

    public static int getAttribDepth() {
            return ctx().attribDepth;
        }
}
