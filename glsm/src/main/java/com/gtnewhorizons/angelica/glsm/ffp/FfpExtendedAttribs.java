package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizon.gtnhlib.client.renderer.cel.api.util.NormI8;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizons.angelica.glsm.GLContextState;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.BatchStateGuard;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.ImmediateExtendedAttribHandler;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memCalloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetByte;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetInt;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetShort;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * Supplies {@code mc_midTexCoord} and {@code at_tangent} to mod geometry drawn through the
 * {@code glVertexPointer}/{@code glTexCoordPointer} client-array path over a VBO.
 *
 * <p>That path bypasses the Tessellator, so the vertex data reaching the draw carries neither
 * attribute. The rebuild reads the source range out of the mod's own buffer, lets the host handler
 * derive both attributes per primitive, and points attribute locations 12/13 at a cached scratch
 * buffer for the duration of the draw.</p>
 *
 * <p>The cache is keyed on the source buffer identities and layouts. Buffers that get respecified
 * often enough within a short span are treated as streaming, which keeps a per-frame rewritten VBO
 * from paying for a rebuild that is invalid by the next frame.</p>
 */
public final class FfpExtendedAttribs {

    private static final int POSITION_LOC = Usage.POSITION.getAttributeLocation();
    private static final int UV_LOC = Usage.PRIMARY_UV.getAttributeLocation();
    private static final int NORMAL_LOC = Usage.NORMAL.getAttributeLocation();
    private static final int SRC_POS_OFFSET = 0;
    private static final int SRC_TEX_OFFSET = 12;
    private static final int SRC_NORMAL_OFFSET = 20;
    private static final int SRC_STRIDE_FLAT = 20;
    private static final int SRC_STRIDE_SMOOTH = 24;
    private static final int MAX_CACHE = 1024;
    private static final int MAX_EXT_VERTS = 1 << 20;
    private static final int MAX_RESPECS = 8;
    private static final int RESPEC_DECAY_FRAMES = 2;
    private static final int MIN_STREAM_SPAN_FRAMES = 4;
    private static final int STREAM_EXPIRE_FRAMES = 60;

    private static final HashMap<Key, ExtBuffer> cache = new HashMap<>();
    private static final Key lookup = new Key();

    private static final HashMap<EboKey, Boolean> eboPatternCache = new HashMap<>();
    private static final EboKey eboLookup = new EboKey();

    private static final Int2ObjectMap<ObjectSet<Key>> keysBySource = new Int2ObjectOpenHashMap<>();
    private static final Int2ObjectMap<ObjectSet<EboKey>> eboKeysBySource = new Int2ObjectOpenHashMap<>();
    private static final Int2IntMap respecCounts = new Int2IntOpenHashMap();
    private static final Int2IntMap respecFrames = new Int2IntOpenHashMap();
    private static final Int2IntMap respecRunStart = new Int2IntOpenHashMap();
    private static final IntSet streamingSources = new IntOpenHashSet();
    private static final IntSet persistentSources = new IntOpenHashSet();
    private static int frame;

    private FfpExtendedAttribs() {}

    /** Advances the frame clock that expires streaming sources and respecification runs. */
    public static void endFrame() {
        frame++;
    }

    /** Whether any rebuilt data or element-buffer verdict is cached, so buffer notifications can skip the work. */
    public static boolean isEmpty() {
        return cache.isEmpty() && eboPatternCache.isEmpty();
    }

    /** Marks an internal draw so the rebuild does not intercept geometry the FFP layer issues on its own behalf. */
    public static void beginInternalDraw() {
        GLStateManager.ctx().internalDrawDepth++;
    }

    public static void endInternalDraw() {
        final GLContextState glCtx = GLStateManager.ctx();
        if (glCtx.internalDrawDepth > 0) glCtx.internalDrawDepth--;
    }

    private static void beginInternalGl() {
        GLStateManager.ctx().internalGlDepth++;
    }

    private static void endInternalGl() {
        final GLContextState glCtx = GLStateManager.ctx();
        if (glCtx.internalGlDepth > 0) glCtx.internalGlDepth--;
    }

    private static boolean isNotConventionalTexturedArray(VAOManager.Attrib a) {
        return a == null || !a.enabled || a.genericPointer || a.vboId == 0 || a.size < 2;
    }

    /**
     * Binds rebuilt attributes for a {@code glDrawArrays} call, returning whether they were bound.
     *
     * <p>Only whole quads and triangles are rebuilt: the handler derives one value set per primitive,
     * so a partial primitive or a mode without a per-primitive normal has no defined result.</p>
     */
    public static boolean maybeBind(GLContextState glCtx, int mode, int first, int count) {
        final ImmediateExtendedAttribHandler handler = GLSMHooks.immediateExtendedHandler;
        if (handler == null || glCtx.internalDrawDepth > 0 || first < 0) return false;
        final int vertsPerPrim = ImmediateExtendedAttribHandler.extPrimVerts(mode, count);
        if (vertsPerPrim == 0 || !handler.wantsExtended()) return false;

        final VAOManager.Attrib pos = glCtx.vaos.attrib(POSITION_LOC);
        final VAOManager.Attrib uv = glCtx.vaos.attrib(UV_LOC);
        if (isNotConventionalTexturedArray(pos) || isNotConventionalTexturedArray(uv)) return false;
        if (isStreaming(pos.vboId) || isStreaming(uv.vboId)) return false;

        VAOManager.Attrib normal = vertsPerPrim == 3 ? glCtx.vaos.attrib(NORMAL_LOC) : null;
        if (normal != null && (normal.genericPointer || !normal.enabled || normal.size < 3 || normal.vboId == 0
            || isStreaming(normal.vboId))) {
            normal = null;
        }

        return bindExt(handler, pos, uv, normal, first, count, vertsPerPrim, mode);
    }

    /**
     * Binds rebuilt attributes for an indexed draw whose element buffer spells quads as six indices,
     * returning whether they were bound.
     *
     * <p>Mod geometry frequently ships quad layouts as indexed triangles, which draws triangles but
     * describes quads. The rebuild keeps the quad grouping by reading the index pattern, so the
     * per-quad values match what the mod authored.</p>
     */
    public static boolean maybeBindIndexed(GLContextState glCtx, int mode, int indexCount, int indexType, long indexOffset) {
        final ImmediateExtendedAttribHandler handler = GLSMHooks.immediateExtendedHandler;
        if (handler == null || glCtx.internalDrawDepth > 0) return false;
        if (mode != GL11.GL_TRIANGLES || indexCount <= 0 || indexCount % 6 != 0) return false;
        if (!handler.wantsExtended()) return false;

        final VAOManager.Attrib pos = glCtx.vaos.attrib(POSITION_LOC);
        final VAOManager.Attrib uv = glCtx.vaos.attrib(UV_LOC);
        if (isNotConventionalTexturedArray(pos) || isNotConventionalTexturedArray(uv)) return false;
        if (isStreaming(pos.vboId) || isStreaming(uv.vboId) || isStreaming(glCtx.vaos.boundEBO)) return false;

        final int indexSize = indexTypeBytes(indexType);
        if (indexSize == 0 || indexOffset % (6L * indexSize) != 0) return false;
        final int first = (int) (indexOffset / indexSize) / 6 * 4;
        final int count = indexCount / 6 * 4;
        if (!isQuadPatternEbo(glCtx.vaos.boundEBO, indexOffset, indexCount, indexType, indexSize, first)) return false;

        return bindExt(handler, pos, uv, null, first, count, 4, GL11.GL_QUADS);
    }

    private static boolean bindExt(ImmediateExtendedAttribHandler handler, VAOManager.Attrib pos, VAOManager.Attrib uv,
                                   VAOManager.Attrib normal, int first, int count, int vertsPerPrim, int mode) {
        final int extVbo = getOrBuild(handler, pos, uv, normal, first, count, vertsPerPrim, mode);
        if (extVbo == 0) return false;

        final int savedVBO = GLSMBuffers.getBoundVBO();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, extVbo);
        ImmediateExtendedAttribHandler.setupExtAttribPointers(0L, ImmediateExtendedAttribHandler.EXT_STRIDE);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVBO);
        return true;
    }

    /** Drops the rebuilt attributes so later draws do not read a buffer the caller may repurpose. */
    public static void unbind() {
        GLSMVertexInput.glDisableVertexAttribArray(ImmediateExtendedAttribHandler.LOC_MID_TEX);
        GLSMVertexInput.glDisableVertexAttribArray(ImmediateExtendedAttribHandler.LOC_TANGENT);
        setNeutralCurrentValues();
    }

    /**
     * Leaves attribute locations 12/13 at values that describe no sprite, so a draw without rebuilt
     * attributes cannot sample a stale mid coordinate. The value writes are bracketed out of the
     * batch flush because they carry no caller state.
     */
    public static void setNeutralCurrentValues() {
        BatchStateGuard.suspend();
        try {
            GLSMCurrentAttribs.glVertexAttrib2f(ImmediateExtendedAttribHandler.LOC_MID_TEX, 0.5f, 0.5f);
            GLSMCurrentAttribs.glVertexAttrib4f(ImmediateExtendedAttribHandler.LOC_TANGENT, 1.0f, 0.0f, 0.0f, 1.0f);
        } finally {
            BatchStateGuard.resume();
        }
    }

    /** Drops everything derived from a deleted buffer. */
    public static void onDeleteBuffer(GLContextState glCtx, int vboId) {
        if (glCtx.internalGlDepth > 0 || vboId == 0) return;
        dropDerivedFrom(vboId);
        glCtx.writeMappedBuffers.remove(vboId);
        respecCounts.remove(vboId);
        respecFrames.remove(vboId);
        respecRunStart.remove(vboId);
        streamingSources.remove(vboId);
        persistentSources.remove(vboId);
    }

    /**
     * Invalidates data derived from a buffer whose contents or storage changed.
     *
     * <p>A buffer respecified several times within a few frames is a per-frame rewrite, so it stops
     * being a rebuild candidate: the rebuilt range would be wrong by the next draw anyway. The first
     * respecification of such a run keeps the verdict open until the run is long enough to be sure,
     * which keeps a one-off update of a static buffer on the rebuild path.</p>
     */
    public static void onBufferRespecified(GLContextState glCtx, int vboId) {
        if (glCtx.internalGlDepth > 0 || vboId == 0) return;
        if (streamingSources.contains(vboId)) {
            respecFrames.put(vboId, frame);
            return;
        }
        if (!dropDerivedFrom(vboId)) return;
        final boolean sameRun = respecCounts.containsKey(vboId) && frame - respecFrames.get(vboId) <= RESPEC_DECAY_FRAMES;
        if (!sameRun) {
            startRespecRun(vboId);
            return;
        }
        final int respecs = respecCounts.get(vboId) + 1;
        final int span = frame - respecRunStart.get(vboId);
        if (respecs >= MAX_RESPECS && span >= MIN_STREAM_SPAN_FRAMES && span <= respecs) {
            markStreaming(vboId);
            return;
        }
        if (span > respecs) {
            startRespecRun(vboId);
            return;
        }
        respecCounts.put(vboId, respecs);
        respecFrames.put(vboId, frame);
    }

    /** Marks a persistently mapped buffer as streaming: its contents change without a respecification call. */
    public static void markPersistent(GLContextState glCtx, int vboId) {
        if (glCtx.internalGlDepth > 0 || vboId == 0) return;
        markStreaming(vboId);
        persistentSources.add(vboId);
    }

    /** Drops every rebuilt range and source verdict. */
    public static void reset() {
        invalidateAll();
        respecCounts.clear();
        respecFrames.clear();
        respecRunStart.clear();
        streamingSources.clear();
        persistentSources.clear();
    }

    private static void startRespecRun(int vboId) {
        respecCounts.put(vboId, 1);
        respecRunStart.put(vboId, frame);
        respecFrames.put(vboId, frame);
    }

    private static void markStreaming(int vboId) {
        respecCounts.remove(vboId);
        respecRunStart.remove(vboId);
        respecFrames.put(vboId, frame);
        if (streamingSources.add(vboId)) dropDerivedFrom(vboId);
    }

    private static boolean isStreaming(int vboId) {
        if (vboId == 0 || !streamingSources.contains(vboId)) return false;
        if (persistentSources.contains(vboId)) return true;
        if (frame - respecFrames.get(vboId) > STREAM_EXPIRE_FRAMES) {
            streamingSources.remove(vboId);
            respecFrames.remove(vboId);
            return false;
        }
        return true;
    }

    private static boolean isQuadPatternEbo(int ebo, long offset, int indexCount, int indexType, int indexSize,
                                            int firstVertex) {
        if (ebo == 0) return false;
        eboLookup.set(ebo, offset, indexCount, indexType);
        final Boolean cached = eboPatternCache.get(eboLookup);
        if (cached != null) return cached;
        if (eboPatternCache.size() >= MAX_CACHE) invalidateAll();

        final ByteBuffer buf = memCalloc(indexCount, indexSize);
        RENDER_BACKEND.getBufferSubData(GL15.GL_ELEMENT_ARRAY_BUFFER, offset, buf);
        final long addr = memAddress0(buf);
        boolean match = true;
        for (int quad = 0, quads = indexCount / 6; quad < quads; quad++) {
            final int base = firstVertex + quad * 4;
            final long p = addr + (long) quad * 6 * indexSize;
            if (readIndex(p, 0, indexSize) != base
                || readIndex(p, 1, indexSize) != base + 1
                || readIndex(p, 2, indexSize) != base + 2
                || readIndex(p, 3, indexSize) != base + 2
                || readIndex(p, 4, indexSize) != base + 3
                || readIndex(p, 5, indexSize) != base) {
                match = false;
                break;
            }
        }
        memFree(buf);

        final EboKey stored = eboLookup.copy();
        eboPatternCache.put(stored, match);
        ObjectSet<EboKey> ebos = eboKeysBySource.get(stored.ebo);
        if (ebos == null) eboKeysBySource.put(stored.ebo, ebos = new ObjectOpenHashSet<>());
        ebos.add(stored);
        return match;
    }

    private static long readIndex(long base, int index, int indexSize) {
        return switch (indexSize) {
            case 1 -> memGetByte(base + index) & 0xFFL;
            case 2 -> memGetShort(base + (long) index * 2) & 0xFFFFL;
            default -> memGetInt(base + (long) index * 4) & 0xFFFFFFFFL;
        };
    }

    private static int indexTypeBytes(int indexType) {
        return switch (indexType) {
            case GL11.GL_UNSIGNED_BYTE -> 1;
            case GL11.GL_UNSIGNED_SHORT -> 2;
            case GL11.GL_UNSIGNED_INT -> 4;
            default -> 0;
        };
    }

    private static int getOrBuild(ImmediateExtendedAttribHandler handler, VAOManager.Attrib pos, VAOManager.Attrib uv,
                                  VAOManager.Attrib normal, int first, int count, int vertsPerPrim, int mode) {
        if (first + count > MAX_EXT_VERTS) return 0;
        lookup.set(pos, uv, normal, mode, first % vertsPerPrim);
        ExtBuffer buf = cache.get(lookup);
        if (buf == null) {
            if (cache.size() >= MAX_CACHE) invalidateAll();
            buf = new ExtBuffer();
            final Key stored = lookup.copy();
            cache.put(stored, buf);
            indexKey(stored);
        }

        final long range = (long) first << 32 | (count & 0xFFFFFFFFL);
        if (buf.vbo != 0 && buf.builtRanges.contains(range)) return buf.vbo;
        if (!ensureCapacity(buf, first + count)) return 0;
        buildRange(handler, buf.vbo, pos, uv, normal, first, count, vertsPerPrim);
        buf.builtRanges.add(range);
        return buf.vbo;
    }

    private static boolean ensureCapacity(ExtBuffer buf, int neededVerts) {
        if (buf.vbo != 0 && buf.capacityVerts >= neededVerts) return true;
        final int extStride = ImmediateExtendedAttribHandler.EXT_STRIDE;
        final int newCapacity = Math.min(MAX_EXT_VERTS, Math.max(neededVerts, buf.capacityVerts * 2));
        beginInternalGl();
        try {
            final int vbo = GLSMBuffers.glGenBuffers();
            if (vbo == 0) return false;
            final int savedVBO = GLSMBuffers.getBoundVBO();
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, (long) newCapacity * extStride, GL15.GL_STATIC_DRAW);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVBO);
            if (buf.vbo != 0) {
                final int savedRead = GLSMBuffers.getBoundCopyReadBuffer();
                final int savedWrite = GLSMBuffers.getBoundCopyWriteBuffer();
                GLSMBuffers.glBindBuffer(GL31.GL_COPY_READ_BUFFER, buf.vbo);
                GLSMBuffers.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, vbo);
                GLSMBuffers.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0L, 0L,
                    (long) buf.capacityVerts * extStride);
                GLSMBuffers.glBindBuffer(GL31.GL_COPY_READ_BUFFER, savedRead);
                GLSMBuffers.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, savedWrite);
                GLSMBuffers.glDeleteBuffers(buf.vbo);
            }
            buf.vbo = vbo;
            buf.capacityVerts = newCapacity;
            return true;
        } finally {
            endInternalGl();
        }
    }

    private static void buildRange(ImmediateExtendedAttribHandler handler, int extVbo, VAOManager.Attrib pos,
                                   VAOManager.Attrib uv, VAOManager.Attrib normal, int first, int count, int vertsPerPrim) {
        final int posStride = pos.effectiveStride();
        final int uvStride = uv.effectiveStride();
        final boolean smooth = normal != null;
        final int normalStride = smooth ? normal.effectiveStride() : 0;
        final int srcStride = smooth ? SRC_STRIDE_SMOOTH : SRC_STRIDE_FLAT;
        final int srcNormalOffset = smooth ? SRC_NORMAL_OFFSET : -1;

        final ByteBuffer posBuf = readRange(pos, first, count, posStride);
        final ByteBuffer uvBuf = readRange(uv, first, count, uvStride);
        final ByteBuffer normalBuf = smooth ? readRange(normal, first, count, normalStride) : null;

        final ByteBuffer src = memAlloc(count * srcStride).order(ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) {
            final int posByte = i * posStride;
            final int uvByte = i * uvStride;
            final int dst = i * srcStride;
            src.putFloat(dst, pos.readComponent(posBuf, posByte, 0));
            src.putFloat(dst + 4, pos.readComponent(posBuf, posByte, 1));
            src.putFloat(dst + 8, pos.size >= 3 ? pos.readComponent(posBuf, posByte, 2) : 0.0f);
            src.putFloat(dst + SRC_TEX_OFFSET, uv.readComponent(uvBuf, uvByte, 0));
            src.putFloat(dst + SRC_TEX_OFFSET + 4, uv.readComponent(uvBuf, uvByte, 1));
            if (smooth) {
                final int normalByte = i * normalStride;
                src.putInt(dst + SRC_NORMAL_OFFSET, NormI8.pack(
                    normal.readComponent(normalBuf, normalByte, 0),
                    normal.readComponent(normalBuf, normalByte, 1),
                    normal.readComponent(normalBuf, normalByte, 2)));
            }
        }

        final int extStride = ImmediateExtendedAttribHandler.EXT_STRIDE;
        final ByteBuffer ext = memCalloc(count, extStride);
        handler.buildPacked(memAddress0(src), srcStride, SRC_POS_OFFSET, SRC_TEX_OFFSET, srcNormalOffset, count,
            vertsPerPrim, memAddress0(ext), extStride);

        beginInternalGl();
        try {
            final int savedVBO = GLSMBuffers.getBoundVBO();
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, extVbo);
            ext.position(0).limit(count * extStride);
            GLSMBuffers.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) first * extStride, ext);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVBO);
        } finally {
            endInternalGl();
        }

        memFree(posBuf);
        memFree(uvBuf);
        if (normalBuf != null) memFree(normalBuf);
        memFree(src);
        memFree(ext);
    }

    private static ByteBuffer readRange(VAOManager.Attrib a, int first, int count, int stride) {
        final int bytes = (count - 1) * stride + a.size * a.typeSizeBytes();
        final ByteBuffer dst = memAlloc(bytes).order(ByteOrder.nativeOrder());
        dst.position(0).limit(bytes);
        final int savedVBO = GLSMBuffers.getBoundVBO();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, a.vboId);
        RENDER_BACKEND.getBufferSubData(GL15.GL_ARRAY_BUFFER, a.offset + (long) first * stride, dst);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVBO);
        dst.position(0).limit(bytes);
        return dst;
    }

    private static boolean dropDerivedFrom(int srcId) {
        final ObjectSet<EboKey> ebos = eboKeysBySource.remove(srcId);
        if (ebos != null) {
            for (EboKey key : ebos) eboPatternCache.remove(key);
        }
        final ObjectSet<Key> keys = keysBySource.get(srcId);
        if (keys == null || keys.isEmpty()) return ebos != null;
        IntArrayList toFree = null;
        for (Key key : keys.toArray(new Key[0])) {
            final ExtBuffer buf = cache.remove(key);
            if (buf != null && buf.vbo != 0) {
                if (toFree == null) toFree = new IntArrayList();
                toFree.add(buf.vbo);
            }
            unindexKey(key);
        }
        freeExtVbos(toFree);
        return true;
    }

    private static void indexKey(Key key) {
        addToBucket(key.posVBO, key);
        if (key.uvVBO != key.posVBO) addToBucket(key.uvVBO, key);
        if (key.normalVBO != key.posVBO && key.normalVBO != key.uvVBO) addToBucket(key.normalVBO, key);
    }

    private static void unindexKey(Key key) {
        removeFromBucket(key.posVBO, key);
        if (key.uvVBO != key.posVBO) removeFromBucket(key.uvVBO, key);
        if (key.normalVBO != key.posVBO && key.normalVBO != key.uvVBO) removeFromBucket(key.normalVBO, key);
    }

    private static void addToBucket(int source, Key key) {
        if (source == 0) return;
        ObjectSet<Key> set = keysBySource.get(source);
        if (set == null) keysBySource.put(source, set = new ObjectOpenHashSet<>());
        set.add(key);
    }

    private static void removeFromBucket(int source, Key key) {
        if (source == 0) return;
        final ObjectSet<Key> set = keysBySource.get(source);
        if (set != null && set.remove(key) && set.isEmpty()) keysBySource.remove(source);
    }

    private static void invalidateAll() {
        eboPatternCache.clear();
        eboKeysBySource.clear();
        invalidateExtBuffers();
    }

    private static void invalidateExtBuffers() {
        if (cache.isEmpty()) return;
        final IntArrayList toFree = new IntArrayList(cache.size());
        for (ExtBuffer buf : cache.values()) {
            if (buf.vbo != 0) toFree.add(buf.vbo);
        }
        cache.clear();
        keysBySource.clear();
        freeExtVbos(toFree);
    }

    private static void freeExtVbos(IntArrayList vbos) {
        if (vbos == null || vbos.isEmpty()) return;
        beginInternalGl();
        try {
            for (int i = 0; i < vbos.size(); i++) {
                GLSMBuffers.glDeleteBuffers(vbos.getInt(i));
            }
        } finally {
            endInternalGl();
        }
    }

    private static final class ExtBuffer {
        int vbo;
        int capacityVerts;
        final LongOpenHashSet builtRanges = new LongOpenHashSet();
    }

    private static final class EboKey {
        int ebo;
        int count;
        int type;
        long offset;

        void set(int ebo, long offset, int count, int type) {
            this.ebo = ebo;
            this.offset = offset;
            this.count = count;
            this.type = type;
        }

        EboKey copy() {
            final EboKey key = new EboKey();
            key.set(ebo, offset, count, type);
            return key;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EboKey other)) return false;
            return ebo == other.ebo && count == other.count && type == other.type && offset == other.offset;
        }

        @Override
        public int hashCode() {
            int h = ebo;
            h = h * 31 + count;
            h = h * 31 + type;
            h = h * 31 + Long.hashCode(offset);
            return h;
        }
    }

    private static final class Key {
        int posVBO;
        int uvVBO;
        int normalVBO;
        int posStride;
        int uvStride;
        int normalStride;
        int posFmt;
        int uvFmt;
        int normalFmt;
        int mode;
        int phase;
        long posOffset;
        long uvOffset;
        long normalOffset;

        void set(VAOManager.Attrib pos, VAOManager.Attrib uv, VAOManager.Attrib normal, int mode, int phase) {
            posVBO = pos.vboId;
            posOffset = pos.offset;
            posStride = pos.effectiveStride();
            posFmt = fmt(pos);
            uvVBO = uv.vboId;
            uvOffset = uv.offset;
            uvStride = uv.effectiveStride();
            uvFmt = fmt(uv);
            if (normal != null) {
                normalVBO = normal.vboId;
                normalOffset = normal.offset;
                normalStride = normal.effectiveStride();
                normalFmt = fmt(normal);
            } else {
                normalVBO = 0;
                normalOffset = 0;
                normalStride = 0;
                normalFmt = 0;
            }
            this.mode = mode;
            this.phase = phase;
        }

        private static int fmt(VAOManager.Attrib a) {
            return (a.size & 0x7F) | (a.normalized ? 0x80 : 0) | (a.type << 8);
        }

        Key copy() {
            final Key key = new Key();
            key.posVBO = posVBO;
            key.posOffset = posOffset;
            key.posStride = posStride;
            key.posFmt = posFmt;
            key.uvVBO = uvVBO;
            key.uvOffset = uvOffset;
            key.uvStride = uvStride;
            key.uvFmt = uvFmt;
            key.normalVBO = normalVBO;
            key.normalOffset = normalOffset;
            key.normalStride = normalStride;
            key.normalFmt = normalFmt;
            key.mode = mode;
            key.phase = phase;
            return key;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key other)) return false;
            return posVBO == other.posVBO && uvVBO == other.uvVBO && normalVBO == other.normalVBO
                && posStride == other.posStride && uvStride == other.uvStride && normalStride == other.normalStride
                && posFmt == other.posFmt && uvFmt == other.uvFmt && normalFmt == other.normalFmt
                && mode == other.mode && phase == other.phase
                && posOffset == other.posOffset && uvOffset == other.uvOffset && normalOffset == other.normalOffset;
        }

        @Override
        public int hashCode() {
            int h = posVBO;
            h = h * 31 + uvVBO;
            h = h * 31 + normalVBO;
            h = h * 31 + posStride;
            h = h * 31 + uvStride;
            h = h * 31 + normalStride;
            h = h * 31 + posFmt;
            h = h * 31 + uvFmt;
            h = h * 31 + normalFmt;
            h = h * 31 + mode;
            h = h * 31 + phase;
            h = h * 31 + Long.hashCode(posOffset);
            h = h * 31 + Long.hashCode(uvOffset);
            h = h * 31 + Long.hashCode(normalOffset);
            return h;
        }
    }
}
