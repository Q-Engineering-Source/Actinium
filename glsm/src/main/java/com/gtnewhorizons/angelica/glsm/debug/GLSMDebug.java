package com.gtnewhorizons.angelica.glsm.debug;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.states.VertexAttribState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetFloat;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetInt;

public final class GLSMDebug {
    private static final Logger LOGGER = LogManager.getLogger("GLSMDebug");
    private static final boolean ENABLE_VERBOSE_DRAW_LOGS = Boolean.getBoolean("actinium.glsm.verboseDrawLogs");
    private static final boolean ENABLE_GL_DEBUG_SYS_PROP = Boolean.getBoolean("actinium.glDebug");
    private static final long OPTION_REFRESH_NS = 500_000_000L;
    private static final int STREAM_LIMIT = 128;
    private static final int QUAD_LIMIT = 512;
    private static final int FFP_LIMIT = 512;
    private static final int DRAW_LIMIT = 256;
    private static final int CLIENT_ARRAY_LIMIT = 64;
    private static final int BUFFER_BUILDER_LIMIT = 512;
    private static final int VERTEX_BUFFER_LIMIT = 256;
    private static final int PROGRAM_DRAW_LIMIT = 1024;

    private static final AtomicInteger streamCount = new AtomicInteger();
    private static final AtomicInteger quadCount = new AtomicInteger();
    private static final AtomicInteger ffpCount = new AtomicInteger();
    private static final AtomicInteger drawCount = new AtomicInteger();
    private static final AtomicInteger clientArrayCount = new AtomicInteger();
    private static final AtomicInteger bufferBuilderCount = new AtomicInteger();
    private static final AtomicInteger vertexBufferCount = new AtomicInteger();
    private static final AtomicInteger programDrawCount = new AtomicInteger();

    private static long lastOptionRefresh;
    private static boolean cachedEnabled;
    private static Method minecraftGetter;
    private static Field worldField;
    private static boolean minecraftReflectionFailed;

    private GLSMDebug() {}

    public static boolean isEnabled() {
        final long now = System.nanoTime();
        if (now - lastOptionRefresh > OPTION_REFRESH_NS) {
            cachedEnabled = readCeleritasDebugOption();
            lastOptionRefresh = now;
        }
        return cachedEnabled;
    }

    public static void logStreamingDraw(
        int drawMode,
        int flags,
        int vertexSize,
        int vertexCount,
        int byteCount,
        int firstVertex,
        boolean persistent,
        int vao,
        int bufferId,
        ByteBuffer packed
    ) {
        if (!shouldLogWorldRender()) return;
        final int count = streamCount.incrementAndGet();
        if (count > STREAM_LIMIT) return;

        LOGGER.info(
            "stream-draw #{} thread={} mode={} flags=0x{} vertexSize={} vertices={} bytes={} firstVertex={} path={} vao={} vbo={} cachedVao={} cachedVbo={} cachedEbo={} attribs=[{}] sample=[{}]",
            count,
            Thread.currentThread().getName(),
            drawModeName(drawMode),
            Integer.toHexString(flags),
            vertexSize,
            vertexCount,
            byteCount,
            firstVertex,
            persistent ? "persistent" : "orphan",
            vao,
            bufferId,
            GLStateManager.getBoundVAO(),
            GLStateManager.getBoundVBO(),
            GLStateManager.getBoundEBO(),
            attributeSummary(),
            vertexSample(packed, flags, vertexSize, vertexCount));
    }

    public static void logQuadConversion(int firstVertex, int vertexCount, int eboId, int prevEbo, long indexOffset) {
        if (!shouldLogWorldRender()) return;
        final int count = quadCount.incrementAndGet();
        if (count > QUAD_LIMIT) return;

        int actualVao = -1;
        int actualEbo = -1;
        try {
            actualVao = BackendManager.RENDER_BACKEND.getInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            actualEbo = BackendManager.RENDER_BACKEND.getInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        } catch (RuntimeException ignored) {
        }

        LOGGER.info(
            "quad-convert #{} firstVertex={} vertices={} quads={} maxVertex={} indexOffset={} ebo={} prevEbo={} cachedEbo={} actualVao={} actualEbo={}",
            count,
            firstVertex,
            vertexCount,
            vertexCount / 4,
            firstVertex + Math.max(0, vertexCount - 1),
            indexOffset,
            eboId,
            prevEbo,
            GLStateManager.getBoundEBO(),
            actualVao,
            actualEbo);
    }

    public static void logFfpPreDraw(int flags) {
        if (!shouldLogWorldRender()) return;
        final int count = ffpCount.incrementAndGet();
        if (count > FFP_LIMIT) return;

        LOGGER.info("ffp-predraw #{} flags=0x{}", count, Integer.toHexString(flags));
    }

    public static void logDrawArrays(String path, int mode, int first, int count) {
        if (!shouldLogWorldRender()) return;
        final int draw = drawCount.incrementAndGet();
        if (draw > DRAW_LIMIT) return;

        LOGGER.info(
            "draw-arrays #{} path={} mode={} first={} count={} vao={} vbo={} ebo={} attribs=[{}]",
            draw,
            path,
            drawModeName(mode),
            first,
            count,
            GLStateManager.getBoundVAO(),
            GLStateManager.getBoundVBO(),
            GLStateManager.getBoundEBO(),
            attributeSummary());
    }

    public static void logDrawElements(String path, int mode, int count, int type, long offset) {
        if (!shouldLogWorldRender()) return;
        final int draw = drawCount.incrementAndGet();
        if (draw > DRAW_LIMIT) return;

        LOGGER.info(
            "draw-elements #{} path={} mode={} count={} type=0x{} offset={} vao={} vbo={} ebo={} attribs=[{}]",
            draw,
            path,
            drawModeName(mode),
            count,
            Integer.toHexString(type),
            offset,
            GLStateManager.getBoundVAO(),
            GLStateManager.getBoundVBO(),
            GLStateManager.getBoundEBO(),
            attributeSummary());
    }

    public static void logClientArrayUpload(int totalBytes, int capacity, int targetVbo, int savedVbo, int[] offsets) {
        if (!shouldLogWorldRender()) return;
        final int count = clientArrayCount.incrementAndGet();
        if (count > CLIENT_ARRAY_LIMIT) return;

        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < VertexAttribState.MAX_ATTRIBS; i++) {
            if (offsets[i] < 0) continue;
            final VertexAttribState.Attrib attrib = GLStateManager.ctx().vao.get(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(i)
                .append(":dst=").append(offsets[i])
                .append(",remaining=").append(attrib.clientPointer == null ? -1 : attrib.clientPointer.remaining())
                .append(",pos=").append(attrib.clientPointer == null ? -1 : attrib.clientPointer.position())
                .append(",lim=").append(attrib.clientPointer == null ? -1 : attrib.clientPointer.limit())
                .append(",size=").append(attrib.size)
                .append(",type=0x").append(Integer.toHexString(attrib.type))
                .append(",stride=").append(attrib.stride)
                .append(",norm=").append(attrib.normalized);
        }

        LOGGER.info(
            "client-array-upload #{} totalBytes={} capacity={} targetVbo={} savedVbo={} vao={} attribs=[{}]",
            count,
            totalBytes,
            capacity,
            targetVbo,
            savedVbo,
            GLStateManager.getBoundVAO(),
            sb);
    }

    public static void logBufferBuilderUpload(
            String format,
            int drawMode,
            int vertexFlags,
            int stride,
            int vertexCount,
            int byteCount,
            int vao,
            int vbo) {
        if (!shouldLogWorldRender()) return;
        final int count = bufferBuilderCount.incrementAndGet();
        if (count > BUFFER_BUILDER_LIMIT) return;

        LOGGER.info(
            "bufferbuilder-upload #{} mode={} flags=0x{} stride={} vertices={} bytes={} vao={} vbo={} format={}",
            count,
            drawModeName(drawMode),
            Integer.toHexString(vertexFlags),
            stride,
            vertexCount,
            byteCount,
            vao,
            vbo,
            format);
    }

    public static void logVertexBufferUpload(String format, int vertexFlags, int stride, int byteCount, int vbo, int vao) {
        if (!shouldLogWorldRender()) return;
        final int count = vertexBufferCount.incrementAndGet();
        if (count > VERTEX_BUFFER_LIMIT) return;

        LOGGER.info(
            "vertexbuffer-upload #{} flags=0x{} stride={} bytes={} vao={} vbo={} format={}",
            count,
            Integer.toHexString(vertexFlags),
            stride,
            byteCount,
            vao,
            vbo,
            format);
    }

    public static void logVertexBufferDraw(String format, int drawMode, int vertexFlags, int stride, int vertexCount, int vbo, int vao) {
        if (!shouldLogWorldRender()) return;
        final int count = vertexBufferCount.incrementAndGet();
        if (count > VERTEX_BUFFER_LIMIT) return;

        LOGGER.info(
            "vertexbuffer-draw #{} mode={} flags=0x{} stride={} vertices={} vao={} vbo={} format={}",
            count,
            drawModeName(drawMode),
            Integer.toHexString(vertexFlags),
            stride,
            vertexCount,
            vao,
            vbo,
            format);
    }

    /**
     * Logs an immediate-mode/streaming draw that lands on a non-zero (shader pipeline) program.
     * Used to diagnose mod content (e.g. Dynamic Surroundings popoffs) rendering through Iris passes.
     */
    public static void logDrawOnActiveProgram(String path, int drawMode, int flags, int vertexCount, int program) {
        if (!shouldLogWorldRender()) return;
        final int count = programDrawCount.incrementAndGet();
        if (count > PROGRAM_DRAW_LIMIT) return;

        LOGGER.info(
            "program-draw #{} path={} program={} mode={} flags=0x{} vertices={} attribs=[{}]",
            count,
            path,
            program,
            drawModeName(drawMode),
            Integer.toHexString(flags),
            vertexCount,
            attributeSummary());
    }

    public static boolean forceOrphanStreaming() {
        return isEnabled();
    }

    public static boolean shouldLogDrawDiagnostics() {
        return shouldLogWorldRender();
    }

    private static boolean readCeleritasDebugOption() {
        try {
            final Class<?> actinium = Class.forName("com.dhj.actinium.Actinium");
            final Method options = actinium.getMethod("options");
            final Object opts = options.invoke(null);
            if (opts == null) return false;
            final Field debugField = opts.getClass().getField("debug");
            final Object debug = debugField.get(opts);
            if (debug == null) return false;
            final Field enabledField = debug.getClass().getField("enableActiniumGlDebug");
            return enabledField.getBoolean(debug);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean shouldLogWorldRender() {
        return (ENABLE_VERBOSE_DRAW_LOGS || ENABLE_GL_DEBUG_SYS_PROP || isEnabled()) && "Client thread".equals(Thread.currentThread().getName()) && isWorldLoaded();
    }

    private static boolean isWorldLoaded() {
        if (minecraftReflectionFailed) return true;

        try {
            if (minecraftGetter == null) {
                final Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
                minecraftGetter = minecraftClass.getMethod("getMinecraft");
                try {
                    worldField = minecraftClass.getField("world");
                } catch (NoSuchFieldException ignored) {
                    worldField = minecraftClass.getField("field_71441_e");
                }
            }

            final Object minecraft = minecraftGetter.invoke(null);
            return minecraft != null && worldField.get(minecraft) != null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            minecraftReflectionFailed = true;
            return true;
        }
    }

    private static String attributeSummary() {
        final StringBuilder sb = new StringBuilder();
        for (int i : new int[] {0, 1, 2, 3, 4}) {
            final VertexAttribState.Attrib attrib = GLStateManager.ctx().vao.get(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(i)
                .append(":en=").append(attrib.enabled)
                .append(",size=").append(attrib.size)
                .append(",type=0x").append(Integer.toHexString(attrib.type))
                .append(",norm=").append(attrib.normalized)
                .append(",stride=").append(attrib.stride)
                .append(",off=").append(attrib.offset)
                .append(",vbo=").append(attrib.vboId);
        }
        return sb.toString();
    }

    private static String vertexSample(ByteBuffer buffer, int flags, int vertexSize, int vertexCount) {
        if (buffer == null || vertexSize <= 0 || vertexCount <= 0) return "none";

        final int position = buffer.position();
        final int limit = buffer.limit();
        final long address = memAddress0(buffer);
        final Layout layout = layoutForFlags(flags);
        final int samples = Math.min(vertexCount, 2);
        final StringBuilder sb = new StringBuilder();
        for (int vertex = 0; vertex < samples; vertex++) {
            final int base = position + vertex * vertexSize;
            if (base + vertexSize > limit) {
                if (sb.length() > 0) sb.append(' ');
                sb.append("v").append(vertex).append("=out-of-range(base=").append(base).append(",limit=").append(limit).append(')');
                continue;
            }

            if (sb.length() > 0) sb.append(' ');
            sb.append("v").append(vertex).append("={");
            final long baseAddress = address + base;
            appendPosition(sb, baseAddress);
            if (layout.tex >= 0) appendUv(sb, baseAddress + layout.tex);
            if (layout.color >= 0) appendInt(sb, "color", baseAddress + layout.color);
            if (layout.light >= 0) appendInt(sb, "light", baseAddress + layout.light);
            if (layout.normal >= 0) appendNormal(sb, baseAddress + layout.normal);
            sb.append('}');
        }
        return sb.toString();
    }

    private static void appendPosition(StringBuilder sb, long offset) {
        sb.append("pos=(")
            .append(trimFloat(memGetFloat(offset))).append(',')
            .append(trimFloat(memGetFloat(offset + 4))).append(',')
            .append(trimFloat(memGetFloat(offset + 8))).append(')');
    }

    private static void appendUv(StringBuilder sb, long offset) {
        sb.append(" uv=(")
            .append(trimFloat(memGetFloat(offset))).append(',')
            .append(trimFloat(memGetFloat(offset + 4))).append(')');
    }

    private static void appendInt(StringBuilder sb, String name, long offset) {
        sb.append(' ').append(name).append("=0x").append(String.format("%08X", memGetInt(offset)));
    }

    private static void appendNormal(StringBuilder sb, long offset) {
        final int packed = memGetInt(offset);
        sb.append(" normal=0x").append(String.format("%08X", packed))
            .append('(')
            .append((byte) packed).append(',')
            .append((byte) (packed >> 8)).append(',')
            .append((byte) (packed >> 16)).append(')');
    }

    private static String trimFloat(float value) {
        if (!Float.isFinite(value)) return Float.toString(value);
        return String.format("%.5f", value);
    }

    private static Layout layoutForFlags(int flags) {
        return switch (flags) {
            case 0x1 -> new Layout(12, -1, -1, -1);
            case 0x2 -> new Layout(-1, 12, -1, -1);
            case 0x4 -> new Layout(-1, -1, -1, 12);
            case 0x8 -> new Layout(-1, -1, 12, -1);
            case 0x5 -> new Layout(12, -1, -1, 20);
            case 0x3 -> new Layout(12, 20, -1, -1);
            case 0x9 -> new Layout(12, -1, 20, -1);
            case 0xa -> new Layout(-1, 12, 16, -1);
            case 0x6 -> new Layout(-1, 16, -1, 12);
            case 0xc -> new Layout(-1, -1, 16, 12);
            case 0xd -> new Layout(12, -1, 20, 24);
            case 0xb -> new Layout(12, 20, 24, -1);
            case 0x7 -> new Layout(12, 20, -1, 24);
            case 0xe -> new Layout(-1, 12, 16, 20);
            case 0xf -> new Layout(16, 12, 24, 28);
            default -> new Layout(-1, -1, -1, -1);
        };
    }

    private record Layout(int tex, int color, int light, int normal) {}

    private static String drawModeName(int mode) {
        return switch (mode) {
            case GL11.GL_POINTS -> "POINTS";
            case GL11.GL_LINES -> "LINES";
            case GL11.GL_LINE_LOOP -> "LINE_LOOP";
            case GL11.GL_LINE_STRIP -> "LINE_STRIP";
            case GL11.GL_TRIANGLES -> "TRIANGLES";
            case GL11.GL_TRIANGLE_STRIP -> "TRIANGLE_STRIP";
            case GL11.GL_TRIANGLE_FAN -> "TRIANGLE_FAN";
            case GL11.GL_QUADS -> "QUADS";
            case GL11.GL_QUAD_STRIP -> "QUAD_STRIP";
            case GL11.GL_POLYGON -> "POLYGON";
            default -> "0x" + Integer.toHexString(mode);
        };
    }
}
