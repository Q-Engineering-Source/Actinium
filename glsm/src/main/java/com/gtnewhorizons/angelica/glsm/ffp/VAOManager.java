package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * Tracks the vertex array object bound to the current context and the vertex array pointers stored in it.
 *
 * <p>Fixed-function client arrays ({@code glVertexPointer} and friends) and generic vertex attributes
 * ({@code glVertexAttribPointer}) are kept in separate slot tables, because compatibility GL lets a mod
 * address the same attribute location through both entry points: the conventional array keeps its own
 * pointer, type and stride while a generic pointer may shadow it, and queries of the conventional pnames
 * keep answering the conventional array. A location is fed by the generic table whenever the generic
 * enable is set, otherwise by the conventional table, otherwise it is disabled.
 *
 * <p>{@link VAOData#attribs} always holds the effective pointer of every location, which is what the
 * client-array upload, the vertex flag derivation and the EBO bookkeeping read.
 */
public final class VAOManager {
    private static final Logger LOGGER = LogManager.getLogger("GLSM");

    public static final int MAX_ATTRIBS = 16;

    private static final boolean DEBUG_DRAW_LOGS = Boolean.getBoolean("actinium.glsm.verboseDrawLogs");
    private static final boolean FULL_CLIENT_ARRAY_UPLOAD = Boolean.getBoolean("actinium.glsmFullClientArrayUpload");

    /** Conventional (fixed-function) client array slots. Locations are resolved through {@link #CONV_LOCATION}. */
    public static final int CONV_POSITION = 0;
    public static final int CONV_NORMAL = 1;
    public static final int CONV_COLOR = 2;
    public static final int CONV_SECONDARY_COLOR = 3;
    public static final int CONV_FOG_COORD = 4;
    public static final int CONV_INDEX = 5;
    public static final int CONV_EDGE_FLAG = 6;
    public static final int CONV_TEX0 = 7;
    public static final int TEX_COORD_UNITS = 8;
    public static final int CONV_COUNT = CONV_TEX0 + TEX_COORD_UNITS;

    /** Conventional slot to vertex attribute location; -1 when the slot has no attribute to feed. */
    private static final int[] CONV_LOCATION = new int[CONV_COUNT];
    /** Vertex attribute location to conventional slot; -1 when the location only carries generic pointers. */
    private static final int[] LOCATION_CONV = new int[MAX_ATTRIBS];

    static {
        Arrays.fill(CONV_LOCATION, -1);
        Arrays.fill(LOCATION_CONV, -1);
        mapConventional(CONV_POSITION, Usage.POSITION.getAttributeLocation());
        mapConventional(CONV_NORMAL, Usage.NORMAL.getAttributeLocation());
        mapConventional(CONV_COLOR, Usage.COLOR.getAttributeLocation());
        // Every accepted client active texture unit (0..MAX_TEXTURE_COORDS-1) feeds its own UV attribute
        // slot; the remaining texcoord slots exist so their pointer and query state is still tracked.
        for (int unit = 0; unit < TEX_COORD_UNITS; unit++) {
            final int location = Usage.uvAttributeLocation(unit);
            if (location >= 0) mapConventional(CONV_TEX0 + unit, location);
        }
    }

    private static void mapConventional(int conv, int location) {
        CONV_LOCATION[conv] = location;
        LOCATION_CONV[location] = conv;
    }

    /**
     * Returns the vertex attribute location a conventional slot feeds, or -1 when the slot carries state
     * without an attribute (index, edge flag, fog coordinate and secondary color have no FFP input).
     */
    public static int conventionalLocation(int conv) {
        return conv >= 0 && conv < CONV_COUNT ? CONV_LOCATION[conv] : -1;
    }

    private static final int T_BYTE = 1, T_UBYTE = 1 << 1, T_SHORT = 1 << 2, T_USHORT = 1 << 3, T_INT = 1 << 4, T_UINT = 1 << 5, T_FLOAT = 1 << 6;
    private static final int T_DOUBLE = 1 << 10, T_HALF = 1 << 11, T_FIXED = 1 << 12, T_PACKED = 1 << 13, T_PACKED_FLOAT = 1 << 14;
    private static final int T_INTEGER = T_BYTE | T_UBYTE | T_SHORT | T_USHORT | T_INT | T_UINT;
    private static final int T_COLOR = T_INTEGER | T_HALF | T_FLOAT | T_DOUBLE | T_PACKED;
    private static final int T_GENERIC = T_COLOR | T_FIXED | T_PACKED_FLOAT;
    private static final int[] LEGAL_TYPES = new int[CONV_COUNT];
    private static final int[] MIN_SIZE = new int[CONV_COUNT];
    private static final int[] MAX_SIZE = new int[CONV_COUNT];

    static {
        legal(CONV_POSITION, T_SHORT | T_INT | T_FLOAT | T_DOUBLE | T_HALF | T_PACKED, 2, 4);
        legal(CONV_NORMAL, T_BYTE | T_SHORT | T_INT | T_HALF | T_FLOAT | T_DOUBLE | T_PACKED, 3, 3);
        legal(CONV_COLOR, T_COLOR, 3, 4);
        legal(CONV_SECONDARY_COLOR, T_COLOR, 3, 4);
        legal(CONV_FOG_COORD, T_HALF | T_FLOAT | T_DOUBLE, 1, 1);
        legal(CONV_INDEX, T_UBYTE | T_SHORT | T_INT | T_FLOAT | T_DOUBLE, 1, 1);
        legal(CONV_EDGE_FLAG, T_UBYTE, 1, 1);
        for (int unit = 0; unit < TEX_COORD_UNITS; unit++) {
            legal(CONV_TEX0 + unit, T_SHORT | T_INT | T_HALF | T_FLOAT | T_DOUBLE | T_PACKED, 1, 4);
        }
    }

    private static void legal(int conv, int types, int minSize, int maxSize) {
        LEGAL_TYPES[conv] = types;
        MIN_SIZE[conv] = minSize;
        MAX_SIZE[conv] = maxSize;
    }

    private static int typeBit(int type) {
        if (type >= GL11.GL_BYTE && type <= GL30.GL_HALF_FLOAT) return 1 << type - GL11.GL_BYTE;
        return switch (type) {
            case GL41.GL_FIXED -> T_FIXED;
            case GL12.GL_UNSIGNED_INT_2_10_10_10_REV, GL33.GL_INT_2_10_10_10_REV -> T_PACKED;
            case GL30.GL_UNSIGNED_INT_10F_11F_11F_REV -> T_PACKED_FLOAT;
            default -> 0;
        };
    }

    private static boolean isLegal(int types, int minSize, int maxSize, boolean bgra, int size, int type) {
        final int bit = typeBit(type);
        if ((types & bit) == 0) return false;
        if (size == GL12.GL_BGRA) return bgra && (bit & (T_UBYTE | T_PACKED)) != 0;
        return size >= minSize && size <= maxSize;
    }

    /** Whether a conventional array accepts this component count and component type. */
    public static boolean isLegalConventional(int conv, int size, int type) {
        if (conv < 0 || conv >= CONV_COUNT) return false;
        return isLegal(LEGAL_TYPES[conv], MIN_SIZE[conv], MAX_SIZE[conv], conv == CONV_COLOR || conv == CONV_SECONDARY_COLOR, size, type);
    }

    /** Whether a generic vertex attribute accepts this component count and component type. */
    public static boolean isLegalGeneric(int index, int size, int type, boolean integer) {
        return index >= 0 && index < MAX_ATTRIBS && isLegal(integer ? T_INTEGER : T_GENERIC, 1, 4, !integer, size, type);
    }

    public final Int2ObjectOpenHashMap<VAOData> vaoMap = new Int2ObjectOpenHashMap<>();
    final Int2ObjectOpenHashMap<VAOData> eboOwners = new Int2ObjectOpenHashMap<>();

    /** Mirror of the GL client attribute stack depth; the saved snapshots are indexed like that stack. */
    private static final int CLIENT_ARRAY_STACK_DEPTH = 16;
    private static final int DELETED_NAME_PRUNE_THRESHOLD = 4096;
    private static final int DEAD_EPOCH = -1;

    private VAOData current;
    private Attrib[] currentAttribs;
    private Attrib[] currentGeneric;
    private Attrib[] currentConventional;
    private int genericEnabled;
    private int conventionalEnabled;
    private int currentVertexFlags;
    private int clientSideEnabledCount;
    public int boundEBO;

    private int arrayVersion;
    private int versionClock;
    private int bufferEpoch;
    private final Int2IntOpenHashMap bufferDeletedAt = new Int2IntOpenHashMap();
    private final int pruneThreshold;
    private final ClientArrays[] savedClientArrays = new ClientArrays[CLIENT_ARRAY_STACK_DEPTH];
    private final Attrib[] restoreBefore = new Attrib[MAX_ATTRIBS];

    private int clientArraysVBO;
    private int clientArraysVBOCapacity;
    private final int[] clientArraysVBOOffsets = new int[MAX_ATTRIBS];
    private final int[] clientArraysVBOUploadLengths = new int[MAX_ATTRIBS];

    public VAOManager() {
        this(DELETED_NAME_PRUNE_THRESHOLD);
    }

    VAOManager(int pruneThreshold) {
        this.pruneThreshold = pruneThreshold;
    }

    private void touch() {
        arrayVersion = ++versionClock;
    }

    private boolean isBufferLive(int buffer, int sinceEpoch) {
        return buffer == 0 || bufferDeletedAt.get(buffer) <= sinceEpoch;
    }

    /** Whether the buffer a tracked pointer was recorded against still exists. */
    public boolean isBufferLive(Attrib a) {
        return isBufferLive(a.vboId, a.epoch);
    }

    public void init(int defaultVAO) {
        vaoMap.clear();
        eboOwners.clear();
        bufferDeletedAt.clear();
        Arrays.fill(savedClientArrays, null);
        Arrays.fill(restoreBefore, null);
        currentVertexFlags = 0;
        clientSideEnabledCount = 0;
        genericEnabled = 0;
        conventionalEnabled = 0;
        currentGeneric = null;
        currentConventional = null;
        arrayVersion = 0;
        versionClock = 0;
        bufferEpoch = 0;
        boundEBO = 0;
        final VAOData data = new VAOData();
        current = data;
        currentAttribs = data.attribs;
        vaoMap.put(defaultVAO, data);
    }

    public void reset(int boundVAO) {
        init(boundVAO);
    }

    public boolean isInitialized() {
        return current != null;
    }

    public void onBindEBO(int ebo) {
        boundEBO = ebo;
    }

    public void onBindVertexArrayPre(int vaoId) {
        if (current != null) {
            current.vertexFlags = currentVertexFlags;
            current.genericEnabled = genericEnabled;
            current.conventionalEnabled = conventionalEnabled;
            current.generic = currentGeneric;
            current.conventional = currentConventional;
            if (current.ebo != boundEBO) {
                removeEboOwner(current.ebo, current);
                current.ebo = boundEBO;
                addEboOwner(current.ebo, current);
            }
            current.clientSideEnabledCount = clientSideEnabledCount;
            current.arrayVersion = arrayVersion;
        }

        VAOData data = vaoMap.get(vaoId);
        if (data == null) {
            data = new VAOData();
            vaoMap.put(vaoId, data);
        }
        current = data;
        currentAttribs = data.attribs;
        currentVertexFlags = data.formatKnown ? data.vertexFlags : currentClientArrayVertexFlags(data.attribs);
        genericEnabled = data.genericEnabled;
        conventionalEnabled = data.conventionalEnabled;
        currentGeneric = data.generic;
        currentConventional = data.conventional;
        boundEBO = data.ebo;
        clientSideEnabledCount = data.clientSideEnabledCount;
        arrayVersion = data.arrayVersion;
    }

    public void onDeleteVertexArray(int vaoId) {
        final VAOData data = vaoMap.remove(vaoId);
        if (data == null) return;
        removeEboOwner(data.ebo, data);
        if (data == current) {
            current = null;
            currentAttribs = null;
            currentGeneric = null;
            currentConventional = null;
            currentVertexFlags = 0;
            clientSideEnabledCount = 0;
            genericEnabled = 0;
            conventionalEnabled = 0;
            boundEBO = 0;
        }
    }

    public void onDeleteBuffer(int buffer) {
        if (buffer == 0) return;
        bufferDeletedAt.put(buffer, ++bufferEpoch);
        if (currentAttribs != null) {
            boolean detached = detach(currentGeneric != null ? currentGeneric : currentAttribs, buffer);
            if (currentConventional != null) detached |= detach(currentConventional, buffer);
            if (detached) touch();
        }
        if (bufferEpoch >= pruneThreshold) pruneDeletedNames();
        VAOData owner = eboOwners.remove(buffer);
        while (owner != null) {
            final VAOData next = owner.nextEboOwner;
            owner.nextEboOwner = null;
            owner.ebo = 0;
            owner = next;
        }
        if (boundEBO == buffer) boundEBO = 0;
        if (current != null && current.ebo == buffer) current.ebo = 0;
    }

    private static boolean detach(Attrib[] slots, int buffer) {
        boolean detached = false;
        for (final Attrib a : slots) {
            if (a != null && a.vboId == buffer) {
                a.vboId = 0;
                detached = true;
            }
        }
        return detached;
    }

    /**
     * Rebases every recorded buffer epoch once the deletion history grows past the prune threshold, so a
     * long session that churns through buffer names keeps a bounded bookkeeping map. Pointers that
     * referenced a deleted buffer keep their dead epoch and stay rejected by {@link #isBufferLive}.
     */
    private void pruneDeletedNames() {
        retireTrackedPointers();
        bufferDeletedAt.clear();
        bufferEpoch = 0;
    }

    private void retireTrackedPointers() {
        for (final VAOData data : vaoMap.values()) {
            retire(data.attribs);
            retire(data.generic);
            retire(data.conventional);
        }
        retire(currentAttribs);
        retire(currentGeneric);
        retire(currentConventional);
        for (final ClientArrays saved : savedClientArrays) {
            if (saved == null) continue;
            saved.vboEpoch = isBufferLive(saved.boundVBO, saved.vboEpoch) ? 0 : DEAD_EPOCH;
            saved.eboEpoch = isBufferLive(saved.boundEBO, saved.eboEpoch) ? 0 : DEAD_EPOCH;
            retire(saved.generic);
            retire(saved.conventional);
        }
    }

    private void retire(@Nullable Attrib[] slots) {
        if (slots == null) return;
        for (final Attrib a : slots) {
            if (a != null) a.epoch = isBufferLive(a.vboId, a.epoch) ? 0 : DEAD_EPOCH;
        }
    }

    private void addEboOwner(int ebo, VAOData data) {
        if (ebo == 0) return;
        data.nextEboOwner = eboOwners.put(ebo, data);
    }

    private void removeEboOwner(int ebo, VAOData data) {
        if (ebo == 0) return;
        VAOData node = eboOwners.get(ebo);
        if (node == data) {
            final VAOData next = data.nextEboOwner;
            if (next == null) eboOwners.remove(ebo);
            else eboOwners.put(ebo, next);
            data.nextEboOwner = null;
            return;
        }
        while (node != null) {
            final VAOData next = node.nextEboOwner;
            if (next == data) {
                node.nextEboOwner = data.nextEboOwner;
                data.nextEboOwner = null;
                return;
            }
            node = next;
        }
    }

    public void enableClientVertexFlag(int flag) {
        currentVertexFlags |= flag;
    }

    public void disableClientVertexFlag(int flag) {
        currentVertexFlags &= ~flag;
    }

    public int getVertexFlags() {
        return currentVertexFlags;
    }

    public void setVertexFlags(int flags) {
        currentVertexFlags = flags;
    }

    public void setFormatVertexFlags(int flags) {
        currentVertexFlags = flags;
        if (current != null) {
            current.vertexFlags = flags;
            current.formatKnown = true;
        }
    }

    public boolean isConventionalEnabled(int conv) {
        return conv >= 0 && conv < CONV_COUNT && (conventionalEnabled >> conv & 1) != 0;
    }

    public static final class VAOData {
        public final Attrib[] attribs = new Attrib[MAX_ATTRIBS];
        public int vertexFlags;
        public boolean formatKnown;
        public int genericEnabled;
        public int conventionalEnabled;
        public @Nullable Attrib[] generic;
        public @Nullable Attrib[] conventional;
        public int ebo;
        public int clientSideEnabledCount;
        int arrayVersion;
        private VAOData nextEboOwner;

        private VAOData() {
            for (int i = 0; i < MAX_ATTRIBS; i++) {
                attribs[i] = new Attrib();
                attribs[i].genericPointer = true;
            }
        }
    }

    public boolean setAttribute(int index, int size, int type, boolean normalized, int stride, long offset, int vboId) {
        return setGeneric(index, size, type, normalized, false, stride, offset, vboId, null);
    }

    public boolean setAttribute(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer, int vboId) {
        return setGeneric(index, size, type, normalized, false, stride, 0, vboId, clientPointerFor(pointer, vboId));
    }

    public boolean setIntegerAttribute(int index, int size, int type, int stride, long offset, int vboId) {
        return setGeneric(index, size, type, false, true, stride, offset, vboId, null);
    }

    public boolean setIntegerAttribute(int index, int size, int type, int stride, ByteBuffer pointer, int vboId) {
        return setGeneric(index, size, type, false, true, stride, 0, vboId, clientPointerFor(pointer, vboId));
    }

    public boolean setConventional(int conv, int size, int type, boolean normalized, int stride, long offset, int vboId) {
        return setConventional(conv, size, type, normalized, stride, offset, vboId, null);
    }

    public boolean setConventional(int conv, int size, int type, boolean normalized, int stride, ByteBuffer pointer) {
        return setConventional(conv, size, type, normalized, stride, 0, 0, clientPointerFor(pointer, 0));
    }

    /** A pointer only describes client memory when no buffer is bound; otherwise the buffer carries the data. */
    private static @Nullable ByteBuffer clientPointerFor(@Nullable ByteBuffer pointer, int vboId) {
        return vboId == 0 && pointer != null ? captureClientPointer(pointer) : null;
    }

    private boolean setGeneric(int index, int size, int type, boolean normalized, boolean integer, int stride, long offset, int vboId, @Nullable ByteBuffer pointer) {
        if (index < 0 || index >= MAX_ATTRIBS) return false;
        final Attrib g = genericSlot(index);
        store(g, size, type, normalized, integer, stride, offset, vboId, pointer);
        if (currentAttribs[index] == g) return true;
        if (!isGenericEnabled(index) && isLocationConventionalEnabled(index)) return false;
        apply(index, g);
        return true;
    }

    private boolean setConventional(int conv, int size, int type, boolean normalized, int stride, long offset, int vboId, @Nullable ByteBuffer pointer) {
        if (conv < 0 || conv >= CONV_COUNT) return false;
        final Attrib c = conventionalSlot(conv);
        store(c, size, type, normalized, false, stride, offset, vboId, pointer);
        final int index = CONV_LOCATION[conv];
        if (index < 0) return false;
        if (currentAttribs[index] == c) return true;
        if (isGenericEnabled(index)) return false;
        apply(index, c);
        return true;
    }

    private void store(Attrib a, int size, int type, boolean normalized, boolean integer, int stride, long offset, int vboId, @Nullable ByteBuffer clientPointer) {
        final boolean wasClient = a.isClientSide();
        a.size = size;
        a.type = type;
        a.normalized = normalized;
        a.integer = integer;
        a.stride = stride;
        a.offset = offset;
        a.vboId = vboId;
        a.epoch = bufferEpoch;
        a.clientPointer = clientPointer;
        if (wasClient != a.isClientSide()) clientSideEnabledCount += wasClient ? -1 : 1;
        touch();
    }

    private void apply(int index, Attrib source) {
        final Attrib old = currentAttribs[index];
        if (old != null) {
            if (old.isClientSide()) clientSideEnabledCount--;
            source.enabled = old.enabled;
            old.enabled = false;
            if (source.isClientSide()) clientSideEnabledCount++;
        }
        currentAttribs[index] = source;
    }

    private boolean isGenericEnabled(int index) {
        return (genericEnabled >> index & 1) != 0;
    }

    private boolean isLocationConventionalEnabled(int index) {
        final int conv = LOCATION_CONV[index];
        return conv >= 0 && isConventionalEnabled(conv);
    }

    private @Nullable Attrib generic(int index) {
        return (currentGeneric != null ? currentGeneric : currentAttribs)[index];
    }

    private Attrib genericSlot(int index) {
        final Attrib[] slots = currentGeneric != null ? currentGeneric : currentAttribs;
        Attrib g = slots[index];
        if (g == null) {
            g = new Attrib();
            g.genericPointer = true;
            slots[index] = g;
        }
        return g;
    }

    private Attrib conventionalSlot(int conv) {
        if (currentConventional == null) {
            currentConventional = new Attrib[CONV_COUNT];
            currentGeneric = currentAttribs.clone();
        }
        Attrib c = currentConventional[conv];
        if (c == null) {
            c = new Attrib();
            currentConventional[conv] = c;
        }
        return c;
    }

    private boolean refresh(int index) {
        touch();
        boolean repoint = false;
        final boolean conventional = isLocationConventionalEnabled(index);
        final Attrib winner = isGenericEnabled(index) ? genericSlot(index) : conventional ? currentConventional[LOCATION_CONV[index]] : null;
        if (winner != null && winner != currentAttribs[index]) {
            apply(index, winner);
            repoint = true;
        }
        final boolean enabled = winner != null;
        Attrib e = currentAttribs[index];
        if (e == null) {
            if (!enabled) return repoint;
            e = genericSlot(index);
        }
        if (e.enabled != enabled) {
            if (e.clientPointer != null) clientSideEnabledCount += enabled ? 1 : -1;
            e.enabled = enabled;
        }
        return repoint;
    }

    /** Enables a generic vertex attribute; returns whether the effective pointer changed and needs a re-point. */
    public boolean enableAttribute(int index) {
        if (index < 0 || index >= MAX_ATTRIBS) return false;
        genericEnabled |= 1 << index;
        return refresh(index);
    }

    /** Disables a generic vertex attribute, falling back to the conventional array when one is enabled. */
    public boolean disableAttribute(int index) {
        if (index < 0 || index >= MAX_ATTRIBS) return false;
        genericEnabled &= ~(1 << index);
        return refresh(index);
    }

    public void setEnabled(int index, boolean enabled) {
        if (enabled) {
            enableAttribute(index);
        } else {
            disableAttribute(index);
        }
    }

    /** Enables or disables a conventional client array; returns whether the effective pointer needs a re-point. */
    public boolean setConventionalEnabled(int conv, boolean enabled) {
        if (conv < 0 || conv >= CONV_COUNT) return false;
        conventionalSlot(conv);
        if (enabled) {
            conventionalEnabled |= 1 << conv;
        } else {
            conventionalEnabled &= ~(1 << conv);
        }
        final int index = CONV_LOCATION[conv];
        if (index >= 0) return refresh(index);
        touch();
        return false;
    }

    /**
     * Applies a conventional client-array enable and keeps the attribute it feeds in sync with the driver:
     * re-points the location when the effective pointer moved and matches the enabled bit.
     */
    public void applyConventionalEnabled(int conv, boolean enabled) {
        final boolean repoint = setConventionalEnabled(conv, enabled);
        final int index = conventionalLocation(conv);
        if (index < 0) return;
        if (repoint) repointAttrib(index);
        syncAttribEnable(index);
    }

    /** Tracks the instancing divisor of a generic attribute; returns whether the generic slot is effective. */
    public boolean setDivisor(int index, int divisor) {
        if (index < 0 || index >= MAX_ATTRIBS) return true;
        final Attrib g = genericSlot(index);
        g.divisor = divisor;
        touch();
        return currentAttribs[index] == g;
    }

    public @Nullable Attrib conventional(int conv) {
        return conv >= 0 && conv < CONV_COUNT && currentConventional != null ? currentConventional[conv] : null;
    }

    /** Re-issues the vertex attribute pointer of a location from its tracked state and its own buffer. */
    public void repointAttrib(int index) {
        if (index < 0 || index >= MAX_ATTRIBS || currentAttribs == null) return;
        final Attrib a = currentAttribs[index];
        if (a == null) return;
        RENDER_BACKEND.vertexAttribDivisor(index, a.divisor);
        if (a.size == 0 || a.vboId == 0 || !isBufferLive(a)) return;
        final int savedVBO = GLStateManager.getBoundVBO();
        if (savedVBO != a.vboId) GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, a.vboId);
        if (a.integer) {
            RENDER_BACKEND.vertexAttribIPointer(index, a.size, a.type, a.stride, a.offset);
        } else {
            RENDER_BACKEND.vertexAttribPointer(index, a.size, a.type, a.normalized, a.stride, a.offset);
        }
        if (GLStateManager.getBoundVBO() != savedVBO) GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVBO);
    }

    /** Drives the vertex attribute array enable of a location from its tracked state. */
    public void syncAttribEnable(int index) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        if (isAttribEnabled(index)) {
            RENDER_BACKEND.enableVertexAttribArray(index);
        } else {
            RENDER_BACKEND.disableVertexAttribArray(index);
        }
    }

    /**
     * Snapshot of the client array state a {@code glPushClientAttrib(GL_CLIENT_VERTEX_ARRAY_BIT)} has to
     * carry: the bound vertex array, both buffer bindings, the client array enables, both slot tables and
     * the buffer epochs needed to reject pointers whose buffer was deleted while pushed.
     */
    public static final class ClientArrays {
        public int mask;
        public int boundVAO;
        public int boundVBO;
        public int boundEBO;
        int arrayVersion;
        int vboEpoch;
        int eboEpoch;
        int vertexFlags;
        int genericEnabled;
        int conventionalEnabled;
        int genericPresent;
        int conventionalPresent;
        int effectiveGeneric;
        final Attrib[] generic = new Attrib[MAX_ATTRIBS];
        final Attrib[] conventional = new Attrib[CONV_COUNT];
    }

    private static void saveSlot(Attrib[] saved, int i, Attrib source) {
        if (saved[i] == null) saved[i] = new Attrib();
        saved[i].copyPointer(source);
    }

    /**
     * Records the client array state a {@code glPushClientAttrib} has to carry. GL keeps the vertex array
     * and array buffer bindings outside this manager, so the caller passes the ones it is tracking.
     */
    public void saveClientArrays(int slot, int mask, int boundVAO, int boundVBO) {
        final ClientArrays out = savedClientArrays(slot);
        if (out == null) return;
        out.mask = mask;
        if ((mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) == 0) return;
        out.arrayVersion = arrayVersion;
        out.vboEpoch = bufferEpoch;
        out.eboEpoch = bufferEpoch;
        out.vertexFlags = currentVertexFlags;
        out.genericEnabled = genericEnabled;
        out.conventionalEnabled = conventionalEnabled;
        out.boundVAO = boundVAO;
        out.boundVBO = boundVBO;
        out.boundEBO = boundEBO;
        out.genericPresent = 0;
        out.conventionalPresent = 0;
        out.effectiveGeneric = 0;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            final Attrib g = generic(i);
            if (g != null) {
                out.genericPresent |= 1 << i;
                saveSlot(out.generic, i, g);
            }
            final Attrib e = currentAttribs[i];
            if (e != null && e.genericPointer) out.effectiveGeneric |= 1 << i;
        }
        if (currentConventional == null) return;
        for (int i = 0; i < CONV_COUNT; i++) {
            final Attrib c = currentConventional[i];
            if (c != null) {
                out.conventionalPresent |= 1 << i;
                saveSlot(out.conventional, i, c);
            }
        }
    }

    ClientArrays savedClientArrays(int slot) {
        if (slot < 0 || slot >= CLIENT_ARRAY_STACK_DEPTH) {
            LOGGER.error("Client array snapshot slot {} is outside the {} supported depths; the vertex array state of this push is dropped", slot, CLIENT_ARRAY_STACK_DEPTH);
            return null;
        }
        ClientArrays saved = savedClientArrays[slot];
        if (saved == null) {
            saved = new ClientArrays();
            savedClientArrays[slot] = saved;
        }
        return saved;
    }

    /** The mask a {@code glPushClientAttrib} at this slot recorded. */
    public int savedClientAttribMask(int slot) {
        final ClientArrays saved = slot >= 0 && slot < CLIENT_ARRAY_STACK_DEPTH ? savedClientArrays[slot] : null;
        return saved != null ? saved.mask : 0;
    }

    public boolean isSavedVboLive(ClientArrays saved) {
        return isBufferLive(saved.boundVBO, saved.vboEpoch);
    }

    public boolean isSavedEboLive(ClientArrays saved) {
        return isBufferLive(saved.boundEBO, saved.eboEpoch);
    }

    /**
     * Restores the client array state a matching {@code glPushClientAttrib} recorded, then drives the
     * driver back to it: the saved vertex array is bound, the changed pointers are re-pointed from their
     * own buffers and the saved buffer bindings are restored. A pointer whose buffer has been deleted
     * since the push is not restored, and a deleted saved vertex array makes the whole restore a no-op.
     *
     * @return whether a snapshot was restored
     */
    public boolean restoreClientArrays(int slot) {
        final ClientArrays saved = slot >= 0 && slot < CLIENT_ARRAY_STACK_DEPTH ? savedClientArrays[slot] : null;
        if (saved == null || (saved.mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) == 0) return false;
        if (saved.boundVAO != 0 && !vaoMap.containsKey(saved.boundVAO)) {
            LOGGER.debug("Client array restore skipped: vertex array {} was deleted after the push", saved.boundVAO);
            return false;
        }
        GLStateManager.glBindVertexArray(saved.boundVAO);
        if (isSavedVboLive(saved)) {
            final int changed = restoreClientArrays(saved);
            for (int pointers = changed & 0xFFFF; pointers != 0; pointers &= pointers - 1) {
                repointAttrib(Integer.numberOfTrailingZeros(pointers));
            }
            for (int enables = changed >>> 16; enables != 0; enables &= enables - 1) {
                syncAttribEnable(Integer.numberOfTrailingZeros(enables));
            }
            if (GLStateManager.getBoundVBO() != saved.boundVBO) GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, saved.boundVBO);
        }
        if (boundEBO != saved.boundEBO && isSavedEboLive(saved)) GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, saved.boundEBO);
        return true;
    }

    /**
     * Writes a snapshot back into the current slot tables and the effective array.
     *
     * @return bit {@code i} set when location {@code i} needs a new pointer, bit {@code 16 + i} set when it
     *         needs its enable re-issued
     */
    private int restoreClientArrays(ClientArrays in) {
        currentVertexFlags = in.vertexFlags;
        if (arrayVersion == in.arrayVersion) return 0;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            if (restoreBefore[i] == null) restoreBefore[i] = new Attrib();
            final Attrib e = currentAttribs[i];
            if (e == null) {
                restoreBefore[i].clearPointer();
                restoreBefore[i].enabled = false;
            } else {
                restoreBefore[i].copyPointer(e);
                restoreBefore[i].enabled = e.enabled;
            }
        }
        genericEnabled = in.genericEnabled;
        conventionalEnabled = in.conventionalEnabled;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            if ((in.genericPresent >> i & 1) != 0) {
                genericSlot(i).copyPointer(in.generic[i]);
            } else {
                final Attrib g = generic(i);
                if (g != null) g.clearPointer();
            }
        }
        if (currentConventional != null || in.conventionalPresent != 0) {
            for (int i = 0; i < CONV_COUNT; i++) {
                if ((in.conventionalPresent >> i & 1) != 0) {
                    conventionalSlot(i).copyPointer(in.conventional[i]);
                } else if (currentConventional != null && currentConventional[i] != null) {
                    currentConventional[i].clearPointer();
                }
            }
        }
        clientSideEnabledCount = 0;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            final int conv = LOCATION_CONV[i];
            final Attrib g = generic(i);
            final Attrib c = conventional(conv);
            final boolean conventional = isLocationConventionalEnabled(i);
            Attrib e;
            if (isGenericEnabled(i)) {
                e = genericSlot(i);
            } else if (conventional) {
                e = conventionalSlot(conv);
            } else {
                e = c == null || (in.effectiveGeneric >> i & 1) != 0 ? g : c;
            }
            if (g != null) g.enabled = false;
            if (c != null) c.enabled = false;
            final Attrib effective = e != null ? e : currentAttribs[i];
            currentAttribs[i] = effective;
            effective.enabled = e != null && (isGenericEnabled(i) || conventional);
            if (effective.isClientSide()) clientSideEnabledCount++;
        }
        int changed = 0;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            final Attrib e = currentAttribs[i];
            final Attrib before = restoreBefore[i];
            if (e == null) {
                if (before.enabled) changed |= 0x10000 << i;
            } else {
                if (e.enabled != before.enabled) changed |= 0x10000 << i;
                if (!e.samePointer(before)) changed |= 1 << i;
            }
            before.clientPointer = null;
        }
        arrayVersion = in.arrayVersion;
        return changed;
    }

    public boolean isAttribEnabled(int index) {
        if (currentAttribs == null || index < 0 || index >= MAX_ATTRIBS) return false;
        final Attrib a = currentAttribs[index];
        return a != null && a.enabled;
    }

    public Attrib attrib(int index) {
        return currentAttribs[index];
    }

    public boolean hasVBOBoundAttrib() {
        if (currentAttribs == null) return false;
        for (Attrib attrib : currentAttribs) {
            if (attrib != null && attrib.enabled && attrib.vboId != 0) return true;
        }
        return false;
    }

    public boolean hasAnyClientSideEnabledAttrib() {
        return clientSideEnabledCount > 0;
    }

    public int currentClientArrayVertexFlags() {
        return currentAttribs == null ? 0 : currentClientArrayVertexFlags(currentAttribs);
    }

    private static int currentClientArrayVertexFlags(Attrib[] attribs) {
        int flags = 0;
        if (attribs[Usage.COLOR.getAttributeLocation()].enabled) flags |= VertexFlags.COLOR_BIT;
        if (attribs[Usage.NORMAL.getAttributeLocation()].enabled) flags |= VertexFlags.NORMAL_BIT;
        if (attribs[Usage.PRIMARY_UV.getAttributeLocation()].enabled) flags |= VertexFlags.TEXTURE_BIT;
        if (attribs[Usage.SECONDARY_UV.getAttributeLocation()].enabled) flags |= VertexFlags.BRIGHTNESS_BIT;
        return flags;
    }

    public void uploadClientArraysToVBO(int first, int count) {
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.GL_CLIENT_ARRAY_UPLOAD) : 0L;
        final boolean fullUpload = FULL_CLIENT_ARRAY_UPLOAD || count < 0;
        int totalBytes = 0;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            clientArraysVBOOffsets[i] = -1;
            final Attrib a = currentAttribs[i];
            if (a == null || !a.enabled || a.clientPointer == null) continue;
            final int uploadLength = fullUpload ? a.clientPointer.remaining() : computeUploadLength(a, first, count);
            clientArraysVBOUploadLengths[i] = uploadLength;
            clientArraysVBOOffsets[i] = totalBytes;
            totalBytes += uploadLength;
        }
        if (totalBytes == 0) {
            if (perfDebugEnabled) GLSMPerfDebug.end(GLSMPerfDebug.Stage.GL_CLIENT_ARRAY_UPLOAD, perfStart);
            return;
        }

        if (clientArraysVBO == 0) clientArraysVBO = RENDER_BACKEND.genBuffers();
        final int savedVBO = GLStateManager.getBoundVBO();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, clientArraysVBO);

        if (totalBytes > clientArraysVBOCapacity) {
            int newCapacity = Math.max(4096, clientArraysVBOCapacity);
            while (newCapacity < totalBytes) newCapacity *= 2;
            clientArraysVBOCapacity = newCapacity;
        }
        RENDER_BACKEND.bufferData(GL15.GL_ARRAY_BUFFER, clientArraysVBOCapacity, GL15.GL_STREAM_DRAW);
        if (DEBUG_DRAW_LOGS) {
            GLSMDebug.logClientArrayUpload(totalBytes, clientArraysVBOCapacity, clientArraysVBO, savedVBO, clientArraysVBOOffsets);
        }

        for (int i = 0; i < MAX_ATTRIBS; i++) {
            if (clientArraysVBOOffsets[i] < 0) continue;
            final Attrib a = currentAttribs[i];
            final ByteBuffer slice = a.clientPointer.duplicate();
            slice.limit(slice.position() + clientArraysVBOUploadLengths[i]);
            RENDER_BACKEND.bufferSubData(GL15.GL_ARRAY_BUFFER, clientArraysVBOOffsets[i], slice);
            if (a.integer) {
                RENDER_BACKEND.vertexAttribIPointer(i, a.size, a.type, a.stride, clientArraysVBOOffsets[i]);
            } else {
                RENDER_BACKEND.vertexAttribPointer(i, a.size, a.type, a.normalized, a.stride, clientArraysVBOOffsets[i]);
            }
        }

        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVBO);
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.GL_CLIENT_ARRAY_UPLOAD, perfStart);
            GLSMPerfDebug.countClientArrayUpload(totalBytes);
        }
    }

    public static int computeUploadLength(Attrib a, int first, int count) {
        final int remaining = a.clientPointer.remaining();
        if (count <= 0) return 0;
        if (a.stride < 0) return remaining;
        final int stride = a.stride > 0 ? a.stride : a.size * a.typeSizeBytes();
        final long lastByteExclusive = (long) (first + count - 1) * stride + (long) a.size * a.typeSizeBytes();
        return (int) Math.min(remaining, lastByteExclusive);
    }

    private static ByteBuffer captureClientPointer(ByteBuffer pointer) {
        final int position = pointer.position();
        final int capacity = pointer.capacity() - position;
        return MemoryUtilities.memByteBuffer(MemoryUtilities.memAddress(pointer), capacity);
    }

    public static final int FIELD_SIZE = 0;
    public static final int FIELD_TYPE = 1 << 8;
    public static final int FIELD_STRIDE = 2 << 8;
    public static final int FIELD_BINDING = 3 << 8;
    public static final int FIELD_POINTER = 4 << 8;

    private static int texCoordConventional(int clientActiveTextureUnit) {
        return CONV_TEX0 + clientActiveTextureUnit;
    }

    /**
     * Maps a conventional client-array query pname to {@code slot | field} where the low byte is the
     * conventional slot and the upper bits select the queried field. Returns -1 for pnames that are not
     * conventional array queries.
     */
    public static int clientArrayField(int pname, int clientActiveTextureUnit) {
        return switch (pname) {
            case GL11.GL_VERTEX_ARRAY_SIZE -> CONV_POSITION | FIELD_SIZE;
            case GL11.GL_VERTEX_ARRAY_TYPE -> CONV_POSITION | FIELD_TYPE;
            case GL11.GL_VERTEX_ARRAY_STRIDE -> CONV_POSITION | FIELD_STRIDE;
            case GL15.GL_VERTEX_ARRAY_BUFFER_BINDING -> CONV_POSITION | FIELD_BINDING;
            case GL11.GL_VERTEX_ARRAY_POINTER -> CONV_POSITION | FIELD_POINTER;
            case GL11.GL_NORMAL_ARRAY_TYPE -> CONV_NORMAL | FIELD_TYPE;
            case GL11.GL_NORMAL_ARRAY_STRIDE -> CONV_NORMAL | FIELD_STRIDE;
            case GL15.GL_NORMAL_ARRAY_BUFFER_BINDING -> CONV_NORMAL | FIELD_BINDING;
            case GL11.GL_NORMAL_ARRAY_POINTER -> CONV_NORMAL | FIELD_POINTER;
            case GL11.GL_COLOR_ARRAY_SIZE -> CONV_COLOR | FIELD_SIZE;
            case GL11.GL_COLOR_ARRAY_TYPE -> CONV_COLOR | FIELD_TYPE;
            case GL11.GL_COLOR_ARRAY_STRIDE -> CONV_COLOR | FIELD_STRIDE;
            case GL15.GL_COLOR_ARRAY_BUFFER_BINDING -> CONV_COLOR | FIELD_BINDING;
            case GL11.GL_COLOR_ARRAY_POINTER -> CONV_COLOR | FIELD_POINTER;
            case GL11.GL_INDEX_ARRAY_TYPE -> CONV_INDEX | FIELD_TYPE;
            case GL11.GL_INDEX_ARRAY_STRIDE -> CONV_INDEX | FIELD_STRIDE;
            case GL15.GL_INDEX_ARRAY_BUFFER_BINDING -> CONV_INDEX | FIELD_BINDING;
            case GL11.GL_INDEX_ARRAY_POINTER -> CONV_INDEX | FIELD_POINTER;
            case GL11.GL_EDGE_FLAG_ARRAY_STRIDE -> CONV_EDGE_FLAG | FIELD_STRIDE;
            case GL15.GL_EDGE_FLAG_ARRAY_BUFFER_BINDING -> CONV_EDGE_FLAG | FIELD_BINDING;
            case GL11.GL_EDGE_FLAG_ARRAY_POINTER -> CONV_EDGE_FLAG | FIELD_POINTER;
            case GL14.GL_SECONDARY_COLOR_ARRAY_SIZE -> CONV_SECONDARY_COLOR | FIELD_SIZE;
            case GL14.GL_SECONDARY_COLOR_ARRAY_TYPE -> CONV_SECONDARY_COLOR | FIELD_TYPE;
            case GL14.GL_SECONDARY_COLOR_ARRAY_STRIDE -> CONV_SECONDARY_COLOR | FIELD_STRIDE;
            case GL15.GL_SECONDARY_COLOR_ARRAY_BUFFER_BINDING -> CONV_SECONDARY_COLOR | FIELD_BINDING;
            case GL14.GL_SECONDARY_COLOR_ARRAY_POINTER -> CONV_SECONDARY_COLOR | FIELD_POINTER;
            case GL14.GL_FOG_COORDINATE_ARRAY_TYPE -> CONV_FOG_COORD | FIELD_TYPE;
            case GL14.GL_FOG_COORDINATE_ARRAY_STRIDE -> CONV_FOG_COORD | FIELD_STRIDE;
            case GL15.GL_FOG_COORDINATE_ARRAY_BUFFER_BINDING -> CONV_FOG_COORD | FIELD_BINDING;
            case GL14.GL_FOG_COORDINATE_ARRAY_POINTER -> CONV_FOG_COORD | FIELD_POINTER;
            case GL11.GL_TEXTURE_COORD_ARRAY_SIZE -> texCoordConventional(clientActiveTextureUnit) | FIELD_SIZE;
            case GL11.GL_TEXTURE_COORD_ARRAY_TYPE -> texCoordConventional(clientActiveTextureUnit) | FIELD_TYPE;
            case GL11.GL_TEXTURE_COORD_ARRAY_STRIDE -> texCoordConventional(clientActiveTextureUnit) | FIELD_STRIDE;
            case GL15.GL_TEXTURE_COORD_ARRAY_BUFFER_BINDING -> texCoordConventional(clientActiveTextureUnit) | FIELD_BINDING;
            case GL11.GL_TEXTURE_COORD_ARRAY_POINTER -> texCoordConventional(clientActiveTextureUnit) | FIELD_POINTER;
            default -> -1;
        };
    }

    /**
     * Answers one field of a conventional client array. An array that was never specified reports the
     * values compatibility GL reports for it: 4 components (3 for the secondary color), {@code GL_FLOAT}
     * components, no stride and no buffer.
     */
    public int clientArrayParam(int key) {
        final int conv = key & 0xFF;
        final Attrib a = conventional(conv);
        final boolean specified = a != null && a.size != 0;
        return switch (key & ~0xFF) {
            case FIELD_SIZE -> specified ? a.size : conv == CONV_SECONDARY_COLOR ? 3 : 4;
            case FIELD_TYPE -> specified ? a.type : GL11.GL_FLOAT;
            case FIELD_STRIDE -> specified ? a.stride : 0;
            default -> specified ? a.vboId : 0;
        };
    }

    /** Maps a client-array enable cap (e.g. {@code GL_VERTEX_ARRAY}) to its conventional slot, or -1. */
    public static int pnameToConventional(int cap, int clientActiveTextureUnit) {
        return switch (cap) {
            case GL11.GL_VERTEX_ARRAY -> CONV_POSITION;
            case GL11.GL_NORMAL_ARRAY -> CONV_NORMAL;
            case GL11.GL_COLOR_ARRAY -> CONV_COLOR;
            case GL11.GL_INDEX_ARRAY -> CONV_INDEX;
            case GL11.GL_EDGE_FLAG_ARRAY -> CONV_EDGE_FLAG;
            case GL14.GL_SECONDARY_COLOR_ARRAY -> CONV_SECONDARY_COLOR;
            case GL14.GL_FOG_COORDINATE_ARRAY -> CONV_FOG_COORD;
            case GL11.GL_TEXTURE_COORD_ARRAY -> texCoordConventional(clientActiveTextureUnit);
            default -> -1;
        };
    }

    public static final class Attrib {
        public boolean enabled;
        public int size;
        public int type;
        public boolean normalized;
        public boolean integer;
        public int stride;
        public long offset;
        public int vboId;
        public int divisor;
        int epoch;
        public ByteBuffer clientPointer;
        public boolean genericPointer;

        public boolean isClientSide() {
            return enabled && clientPointer != null;
        }

        void copyPointer(Attrib source) {
            size = source.size;
            type = source.type;
            normalized = source.normalized;
            integer = source.integer;
            stride = source.stride;
            offset = source.offset;
            vboId = source.vboId;
            divisor = source.divisor;
            epoch = source.epoch;
            clientPointer = source.clientPointer;
        }

        boolean samePointer(Attrib other) {
            return size == other.size && type == other.type && normalized == other.normalized && integer == other.integer
                && stride == other.stride && offset == other.offset && vboId == other.vboId && divisor == other.divisor
                && clientPointer == other.clientPointer;
        }

        void clearPointer() {
            size = 0;
            type = 0;
            normalized = false;
            integer = false;
            stride = 0;
            offset = 0;
            vboId = 0;
            divisor = 0;
            clientPointer = null;
        }

        public int effectiveStride() {
            return stride != 0 ? stride : size * typeSizeBytes();
        }

        public int typeSizeBytes() {
            return glTypeSizeBytes(type);
        }

        public static int glTypeSizeBytes(int glType) {
            return switch (glType) {
                case GL11.GL_FLOAT, GL11.GL_INT, GL11.GL_UNSIGNED_INT -> 4;
                case GL11.GL_DOUBLE -> 8;
                case GL11.GL_SHORT, GL11.GL_UNSIGNED_SHORT -> 2;
                case GL11.GL_BYTE, GL11.GL_UNSIGNED_BYTE -> 1;
                default -> 4;
            };
        }

        public float readComponent(ByteBuffer buf, int base, int component) {
            return switch (type) {
                case GL11.GL_FLOAT -> buf.getFloat(base + component * 4);
                case GL11.GL_DOUBLE -> (float) buf.getDouble(base + component * 8);
                case GL11.GL_INT -> {
                    final int value = buf.getInt(base + component * 4);
                    yield normalized ? value / (float) Integer.MAX_VALUE : (float) value;
                }
                case GL11.GL_UNSIGNED_INT -> {
                    final int value = buf.getInt(base + component * 4);
                    yield normalized ? (value & 0xFFFFFFFFL) / (float) 0xFFFFFFFFL : (float) (value & 0xFFFFFFFFL);
                }
                case GL11.GL_SHORT -> {
                    final short value = buf.getShort(base + component * 2);
                    yield normalized ? value / (float) Short.MAX_VALUE : (float) value;
                }
                case GL11.GL_UNSIGNED_SHORT -> {
                    final int value = buf.getShort(base + component * 2) & 0xFFFF;
                    yield normalized ? value / (float) 0xFFFF : (float) value;
                }
                case GL11.GL_BYTE -> {
                    final byte value = buf.get(base + component);
                    yield normalized ? value / 127.0f : (float) value;
                }
                case GL11.GL_UNSIGNED_BYTE -> {
                    final int value = buf.get(base + component) & 0xFF;
                    yield normalized ? value / 255.0f : (float) value;
                }
                default -> 0.0f;
            };
        }
    }
}
