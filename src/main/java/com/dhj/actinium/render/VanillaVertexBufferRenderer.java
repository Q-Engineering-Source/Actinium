package com.dhj.actinium.render;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.QuadConverter;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class VanillaVertexBufferRenderer {
    private static final Logger LOGGER = LogManager.getLogger("VanillaVertexBufferRenderer");
    private static final boolean DRAW_STATE_DEBUG = Boolean.getBoolean("actinium.streamingDrawStateDebug");
    private static final Set<Integer> WARNED_UNSUPPORTED_UNITS = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Integer> VAOS_BY_VBO = new HashMap<>();
    private static final Map<Integer, Integer> FLAGS_BY_VBO = new HashMap<>();
    // Birth context handle per VAO: container objects do not survive a display context
    // migration (issue #150); entries born on another context must be recreated.
    private static final Map<Integer, Long> VAO_CONTEXTS = new HashMap<>();

    private VanillaVertexBufferRenderer() {
    }

    public static void uploadVertexBuffer(VertexFormat format, int vbo, ByteBuffer data, int usage) {
        int savedVbo = GLStateManager.getBoundVBO();
        ByteBuffer upload = data.duplicate();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, upload, usage);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
        ensureVertexArray(vbo, format);

        if (GLSMDebug.shouldLogDrawDiagnostics()) {
            GLSMDebug.logVertexBufferUpload(format.toString(), vertexFlags(format), format.getSize(), upload.limit(), vbo, VAOS_BY_VBO.get(vbo));
        }
    }

    public static void drawVertexBuffer(VertexFormat format, int vbo, int count, int mode) {
        if (count <= 0 || vbo < 0) {
            return;
        }

        ensureVertexArray(vbo, format);
        int vao = VAOS_BY_VBO.get(vbo);
        int flags = FLAGS_BY_VBO.get(vbo);
        int savedVao = GLStateManager.getBoundVAO();
        int savedVbo = GLStateManager.getBoundVBO();

        GLStateManager.glBindVertexArray(vao);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLStateManager.prepareWideLineEmulation(mode);
        ShaderManager.getInstance().preDraw(flags);

        boolean logDrawDiagnostics = GLSMDebug.shouldLogDrawDiagnostics();
        boolean checkDrawErrors = RenderDebugHooksHolder.shouldCaptureGlState();
        String formatDescription = logDrawDiagnostics || checkDrawErrors ? format.toString() : null;
        if (checkDrawErrors) {
            RenderDebugHooksHolder.checkDrawError("vertexbuffer:after-predraw", "VertexBuffer", mode, flags, format.getSize(), count, formatDescription, vao, vbo);
        }
        if (logDrawDiagnostics) {
            GLSMDebug.logVertexBufferDraw(formatDescription, mode, flags, format.getSize(), count, vbo, vao);
        }
        drawArrays(mode, count);
        if (checkDrawErrors) {
            RenderDebugHooksHolder.checkDrawError("vertexbuffer:after-draw", "VertexBuffer", mode, flags, format.getSize(), count, formatDescription, vao, vbo);
        }

        GLStateManager.glBindVertexArray(savedVao);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
        if (checkDrawErrors) {
            RenderDebugHooksHolder.checkDrawError("vertexbuffer:after-restore", "VertexBuffer", mode, flags, format.getSize(), count, formatDescription, vao, vbo);
        }
    }

    public static void deleteVertexBuffer(int vbo) {
        Integer vao = VAOS_BY_VBO.remove(vbo);
        FLAGS_BY_VBO.remove(vbo);
        if (vao != null) {
            VAO_CONTEXTS.remove(vao);
            GLStateManager.glDeleteVertexArrays(vao);
        }
        GLStateManager.glDeleteBuffers(vbo);
    }

    /**
     * Drops cached VAO entries born on another GL context: VAOs do not survive a display context
     * migration (issue #150). The VBOs are shared across contexts and stay valid; mismatched VAOs
     * are recreated lazily on next use.
     */
    public static void recreateVertexArrays() {
        final long current = RENDER_BACKEND.getContextHandle();
        VAOS_BY_VBO.entrySet().removeIf(entry -> {
            final int vao = entry.getValue();
            final Long birth = VAO_CONTEXTS.get(vao);
            if (birth == null || birth == current) {
                return false;
            }
            FLAGS_BY_VBO.remove(entry.getKey());
            VAO_CONTEXTS.remove(vao);
            return true;
        });
    }

    /** True when the VAO was created on the calling thread's current context (or is untracked). */
    static boolean vaoMatchesCurrentContext(int vao) {
        final Long birth = VAO_CONTEXTS.get(vao);
        return birth == null || birth == RENDER_BACKEND.getContextHandle();
    }

    public static int createStreamingVertexArray(VertexFormat format, int vbo) {
        int savedVao = GLStateManager.getBoundVAO();
        int savedVbo = GLStateManager.getBoundVBO();
        int vao = GLStateManager.glGenVertexArrays();
        VAO_CONTEXTS.put(vao, RENDER_BACKEND.getContextHandle());
        GLStateManager.glBindVertexArray(vao);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        setupVertexFormatAttributes(format);
        QuadConverter.attachSharedEboToCurrentVao();
        if (DRAW_STATE_DEBUG) {
            int actualVao = BackendManager.RENDER_BACKEND.getInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
            int actualVbo = BackendManager.RENDER_BACKEND.getInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            System.out.println("create-streaming-vao vao=" + vao
                + " vbo=" + vbo
                + " actualVao=" + actualVao
                + " actualVbo=" + actualVbo
                + " format=" + format);
        }
        GLStateManager.glBindVertexArray(savedVao);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
        return vao;
    }

    public static void drawArrays(int drawMode, int vertexCount) {
        drawArrays(drawMode, 0, vertexCount);
    }

    public static void drawArrays(int drawMode, int firstVertex, int vertexCount) {
        if (drawMode == GL11.GL_QUADS) {
            QuadConverter.drawQuadsAsTriangles(firstVertex, vertexCount);
        } else if (drawMode == GL11.GL_QUAD_STRIP) {
            RENDER_BACKEND.drawArrays(GL11.GL_TRIANGLE_STRIP, firstVertex, vertexCount & ~1);
        } else if (drawMode == GL11.GL_POLYGON) {
            RENDER_BACKEND.drawArrays(GL11.GL_TRIANGLE_FAN, firstVertex, vertexCount);
        } else {
            RENDER_BACKEND.drawArrays(drawMode, firstVertex, vertexCount);
        }
    }

    public static int vertexFlags(VertexFormat format) {
        boolean hasTexture = false;
        boolean hasColor = false;
        boolean hasNormal = false;
        boolean hasBrightness = false;

        for (VertexFormatElement element : format.getElements()) {
            switch (element.getUsage()) {
                case COLOR -> hasColor = true;
                case NORMAL -> hasNormal = true;
                case UV -> {
                    if (element.getIndex() == 0) {
                        hasTexture = true;
                    } else if (element.getIndex() == 1) {
                        hasBrightness = true;
                    }
                }
                default -> {
                }
            }
        }

        return VertexFlags.convertToFlags(hasTexture, hasColor, hasNormal, hasBrightness);
    }

    private static void ensureVertexArray(int vbo, VertexFormat format) {
        if (VAOS_BY_VBO.containsKey(vbo)) {
            return;
        }

        int vao = createStreamingVertexArray(format, vbo);
        VAOS_BY_VBO.put(vbo, vao);
        FLAGS_BY_VBO.put(vbo, vertexFlags(format));
    }

    private static void setupVertexFormatAttributes(VertexFormat format) {
        int stride = format.getSize();
        for (int i = 0; i < format.getElementCount(); i++) {
            VertexFormatElement element = format.getElement(i);
            int location = attributeLocation(element);
            if (location < 0) {
                reportUnsupportedElement(element);
                continue;
            }

            GLStateManager.glEnableVertexAttribArray(location);
            GLStateManager.glVertexAttribPointer(
                    location,
                    element.getElementCount(),
                    element.getType().getGlConstant(),
                    isNormalized(element),
                    stride,
                    format.getOffset(i));
        }
    }

    /**
     * Resolves the generic vertex-attribute slot a vanilla {@link VertexFormatElement} feeds.
     * A UV element's {@code index} is the legacy texture unit it belongs to (vanilla's
     * {@code WorldVertexBufferUploader} dispatches each UV element through
     * {@code setClientActiveTexture(TEXTURE0 + index)}), so UV elements must resolve through the
     * shared unit-to-slot table ({@link Usage#uvAttributeLocation}) rather than being limited to
     * units 0/1; dropping units 2/3 there left the FFP texenv chain of Xaero's map (which enables
     * texture units 0/2/3 over one multi-UV vertex format) sampling a single texel per texture
     * (issue #175). Both Xaero's minimap and world map draw their terrain this way.
     *
     * @param element format element to place
     * @return the attribute slot in {@code [0, 15]}, or {@code -1} when the element owns no slot
     */
    static int attributeLocation(VertexFormatElement element) {
        return switch (element.getUsage()) {
            case POSITION -> 0;
            case COLOR -> 1;
            case UV -> Usage.uvAttributeLocation(element.getIndex());
            case NORMAL -> 4;
            case GENERIC -> element.getIndex();
            default -> -1;
        };
    }

    /**
     * Reports a format element the FFP pipeline cannot feed. A legacy texture unit without a
     * slot silently loses its texture coordinates, which is how issue #175 stayed hidden; PADDING
     * legitimately owns no slot and stays quiet.
     */
    private static void reportUnsupportedElement(VertexFormatElement element) {
        if (element.getUsage() != VertexFormatElement.EnumUsage.UV) {
            return;
        }
        final int textureUnit = element.getIndex();
        if (WARNED_UNSUPPORTED_UNITS.add(textureUnit)) {
            LOGGER.warn(
                "Vertex format feeds legacy texture unit {} but the fixed-function pipeline has no attribute slot for it (supported 0..3); its texture coordinates are dropped",
                textureUnit);
        }
    }

    private static boolean isNormalized(VertexFormatElement element) {
        return element.getUsage() == VertexFormatElement.EnumUsage.COLOR
                || element.getUsage() == VertexFormatElement.EnumUsage.NORMAL;
    }
}
