package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Per-VAO vertex attribute pointer state tracking (Mesa-style).
 */
public class VertexAttribState {
    public static final int MAX_ATTRIBS = 16;
    public static final int MAX_VERTEX_BINDINGS = 16;

    private final Int2ObjectOpenHashMap<Attrib[]> vaoAttribs = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Attrib[]> vaoGenericAttribs = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Attrib[]> vaoConventionalAttribs = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Binding[]> vaoBindings = new Int2ObjectOpenHashMap<>();
    private final Int2LongOpenHashMap vaoGenerations = new Int2LongOpenHashMap();
    private long shareGroupId;
    private BufferGenerationProvider bufferGenerations;
    private Attrib[] current;
    private Attrib[] currentGeneric;
    private Attrib[] currentConventional;
    private Binding[] currentBindings;
    private int currentVAO = -1;
    private final ArrayDeque<Attrib[]> pool = new ArrayDeque<>();
    private int clientSideEnabledCount;
    private long nextVaoGeneration = 1L;

    /** Creates a vertex-array model with the explicit share-group lifetime provider that owns its buffer names. */
    public VertexAttribState(long shareGroupId, BufferGenerationProvider bufferGenerations) {
        setShareGroup(shareGroupId, bufferGenerations);
        initForContext(0);
    }

    /** Rebinds an active model to the replacement native context's share-group tracker. */
    public void resetForShareGroup(long shareGroupId, BufferGenerationProvider bufferGenerations) {
        setShareGroup(shareGroupId, bufferGenerations);
        resetState(0);
    }

    private void setShareGroup(long shareGroupId, BufferGenerationProvider bufferGenerations) {
        if (shareGroupId <= 0L) {
            throw new IllegalArgumentException("Share-group id must be positive: " + shareGroupId);
        }
        this.shareGroupId = shareGroupId;
        this.bufferGenerations = Objects.requireNonNull(bufferGenerations, "bufferGenerations");
    }

    /** Activates the current native context's VAO model, preserving recorded state when its VAO changes. */
    public static void init(int defaultVAO) {
        GLStateManager.getVertexAttribState().initForContext(defaultVAO);
    }

    /**
     * Activates the specified VAO model. Newly created context models start with VAO zero, so early
     * legacy client-array state can be copied onto the generated default VAO during GL setup.
     */
    public void initForContext(int defaultVAO) {
        if (current == null) {
            clearTrackedState();
            current = allocAttribArray();
            currentGeneric = allocAttribArray();
            currentConventional = allocAttribArray();
            currentBindings = allocBindingArray();
            currentVAO = defaultVAO;
            putCurrentVAO(defaultVAO);
            clientSideEnabledCount = 0;
            return;
        }

        if (currentVAO == defaultVAO) {
            putCurrentVAO(defaultVAO);
            return;
        }

        if (vaoAttribs.containsKey(defaultVAO)) {
            bindVertexArray(defaultVAO);
            return;
        }

        current = copyAttribs(current);
        currentGeneric = copyAttribs(currentGeneric);
        currentConventional = copyAttribs(currentConventional);
        currentBindings = copyBindings(currentBindings);
        currentVAO = defaultVAO;
        putCurrentVAO(defaultVAO);
        recomputeClientSideCount();
    }

    private void putCurrentVAO(int vao) {
        vaoAttribs.put(vao, current);
        vaoGenericAttribs.put(vao, currentGeneric);
        vaoConventionalAttribs.put(vao, currentConventional);
        vaoBindings.put(vao, currentBindings);
        vaoGenerations.computeIfAbsent(vao, ignored -> nextVaoGeneration++);
    }

    /** Selects the VAO model belonging to the currently bound GL context. */
    public static void onBindVertexArray(int newVAO) {
        GLStateManager.getVertexAttribState().bindVertexArray(newVAO);
    }

    public void bindVertexArray(int newVAO) {
        current = vaoAttribs.computeIfAbsent(newVAO, k -> allocAttribArray());
        currentGeneric = vaoGenericAttribs.computeIfAbsent(newVAO, k -> allocAttribArray());
        currentConventional = vaoConventionalAttribs.computeIfAbsent(newVAO, k -> allocAttribArray());
        currentBindings = vaoBindings.computeIfAbsent(newVAO, k -> allocBindingArray());
        vaoGenerations.computeIfAbsent(newVAO, ignored -> nextVaoGeneration++);
        currentVAO = newVAO;
        recomputeClientSideCount();
    }

    /** Removes the VAO model from the currently bound GL context. */
    public static void onDeleteVertexArray(int vaoId) {
        GLStateManager.getVertexAttribState().deleteVertexArray(vaoId);
    }

    /** Invalidates stale models when a driver recycles a VAO integer name. */
    public static void onCreateVertexArray(int vaoId) {
        GLStateManager.getVertexAttribState().createVertexArray(vaoId);
    }

    public void deleteVertexArray(int vaoId) {
        final Attrib[] arr = vaoAttribs.remove(vaoId);
        if (arr != null) {
            pool.addLast(arr);
        }
        vaoGenericAttribs.remove(vaoId);
        vaoConventionalAttribs.remove(vaoId);
        vaoBindings.remove(vaoId);
        vaoGenerations.remove(vaoId);
        if (currentVAO == vaoId) {
            currentVAO = -1;
            current = null;
            currentGeneric = null;
            currentConventional = null;
            currentBindings = null;
            clientSideEnabledCount = 0;
        }
    }

    /** Starts a fresh tracked lifetime when a native VAO name is generated or recycled. */
    public void createVertexArray(int vaoId) {
        deleteVertexArray(vaoId);
        vaoGenerations.put(vaoId, nextVaoGeneration++);
    }

    /** Returns the current lifetime of a VAO name in this context. */
    public long vertexArrayGeneration(int vaoId) {
        return vaoGenerations.getOrDefault(vaoId, 0L);
    }

    /** Checks whether a VAO name still represents a saved lifetime. */
    public boolean isVertexArrayGenerationCurrent(int vaoId, long generation) {
        return generation != 0L && vertexArrayGeneration(vaoId) == generation;
    }

    /** Clears the vertex model belonging to the currently bound GL context. */
    public static void reset() {
        final VertexAttribState state = GLStateManager.getVertexAttribState();
        final int boundVAO = GLStateManager.getBoundVAO();
        state.resetState(boundVAO);
    }

    /** Clears active VAO tracking while keeping a ready model for later client-array calls. */
    public void resetState() {
        if (currentVAO < 0) {
            throw new IllegalStateException("Cannot reset a retired vertex-array model");
        }
        resetState(currentVAO);
    }

    /** Clears all VAO tracking and allocates empty arrays for the VAO that remains bound. */
    public void resetState(int activeVAO) {
        clearTrackedState();
        initForContext(activeVAO);
    }

    /** Releases model arrays after their context identity has been removed from the registry; this is terminal. */
    public void releaseState() {
        clearTrackedState();
        current = null;
        currentGeneric = null;
        currentConventional = null;
        currentBindings = null;
        currentVAO = -1;
        clientSideEnabledCount = 0;
    }

    private void clearTrackedState() {
        vaoAttribs.clear();
        vaoGenericAttribs.clear();
        vaoConventionalAttribs.clear();
        vaoBindings.clear();
        vaoGenerations.clear();
        pool.clear();
        current = null;
        currentGeneric = null;
        currentConventional = null;
        currentBindings = null;
        currentVAO = -1;
        clientSideEnabledCount = 0;
    }

    private Attrib[] allocAttribArray() {
        Attrib[] arr = pool.pollFirst();
        if (arr != null) {
            for (Attrib a : arr) a.reset();
            return arr;
        }
        arr = new Attrib[MAX_ATTRIBS];
        for (int i = 0; i < MAX_ATTRIBS; i++) arr[i] = new Attrib();
        return arr;
    }

    private static Binding[] allocBindingArray() {
        final Binding[] bindings = new Binding[MAX_VERTEX_BINDINGS];
        for (int i = 0; i < bindings.length; i++) {
            bindings[i] = new Binding();
        }
        return bindings;
    }

    public static void set(int index, int size, int type, boolean normalized, int stride, long offset, int vboId) {
        GLStateManager.getVertexAttribState().setPointer(index, size, type, normalized, stride, offset, vboId);
    }

    public void setPointer(int index, int size, int type, boolean normalized, int stride, long offset, int vboId) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        setPointer(currentGeneric, index, size, type, normalized, stride, offset, vboId, false, PointerSource.GENERIC);
        copyPointer(current[index], currentGeneric[index], PointerSource.GENERIC);
        recomputeClientSideCount();
    }

    public static void set(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer, int vboId) {
        GLStateManager.getVertexAttribState().setPointer(index, size, type, normalized, stride, pointer, vboId);
    }

    public void setPointer(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer, int vboId) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        setPointer(currentGeneric, index, size, type, normalized, stride, 0L, vboId, false,
            (vboId == 0 && pointer != null) ? captureClientPointer(pointer) : null, PointerSource.GENERIC);
        copyPointer(current[index], currentGeneric[index], PointerSource.GENERIC);
        recomputeClientSideCount();
    }

    /** Tracks integer generic pointers separately so restoration uses the integer pointer API. */
    public static void setIntegerPointer(int index, int size, int type, int stride, long offset, int vboId) {
        GLStateManager.getVertexAttribState().setIntegerPointerForContext(index, size, type, stride, offset, vboId, null);
    }

    /** Tracks integer client pointers while retaining the full native allocation range. */
    public static void setIntegerPointer(int index, int size, int type, int stride, ByteBuffer pointer, int vboId) {
        GLStateManager.getVertexAttribState().setIntegerPointerForContext(index, size, type, stride, 0L, vboId, pointer);
    }

    public void setIntegerPointerForContext(
        int index, int size, int type, int stride, long offset, int vboId, ByteBuffer pointer
    ) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final ByteBuffer captured = vboId == 0 && pointer != null ? captureClientPointer(pointer) : null;
        setPointer(currentGeneric, index, size, type, false, stride, offset, vboId, true, captured,
            PointerSource.GENERIC);
        copyPointer(current[index], currentGeneric[index], PointerSource.GENERIC);
        recomputeClientSideCount();
    }

    /** Captures the legacy vertex/color/normal/texcoord pointer separately from generic attributes. */
    public static void setConventional(
        int index, int size, int type, boolean normalized, int stride, long offset, int vboId
    ) {
        GLStateManager.getVertexAttribState().setConventionalPointer(index, size, type, normalized, stride, offset, vboId);
    }

    /** Captures a client-memory conventional pointer without consulting its mutable buffer limit. */
    public static void setConventional(
        int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer, int vboId
    ) {
        GLStateManager.getVertexAttribState().setConventionalPointer(index, size, type, normalized, stride, pointer, vboId);
    }

    public void setConventionalPointer(int index, int size, int type, boolean normalized, int stride, long offset, int vboId) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        setPointer(currentConventional, index, size, type, normalized, stride, offset, vboId, false,
            PointerSource.CONVENTIONAL);
        copyPointer(current[index], currentConventional[index], PointerSource.CONVENTIONAL);
        recomputeClientSideCount();
    }

    public void setConventionalPointer(
        int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer, int vboId
    ) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final ByteBuffer captured = vboId == 0 && pointer != null ? captureClientPointer(pointer) : null;
        setPointer(currentConventional, index, size, type, normalized, stride, 0L, vboId, false, captured,
            PointerSource.CONVENTIONAL);
        copyPointer(current[index], currentConventional[index], PointerSource.CONVENTIONAL);
        recomputeClientSideCount();
    }

    private void setPointer(
        Attrib[] target,
        int index,
        int size,
        int type,
        boolean normalized,
        int stride,
        long offset,
        int vboId,
        boolean integer,
        PointerSource source
    ) {
        setPointer(target, index, size, type, normalized, stride, offset, vboId, integer, null, source);
    }

    private void setPointer(
        Attrib[] target,
        int index,
        int size,
        int type,
        boolean normalized,
        int stride,
        long offset,
        int vboId,
        boolean integer,
        ByteBuffer clientPointer,
        PointerSource source
    ) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final Attrib attrib = target[index];
        attrib.size = size;
        attrib.type = type;
        attrib.normalized = normalized;
        attrib.integer = integer;
        attrib.stride = stride;
        attrib.offset = offset;
        attrib.vboId = vboId;
        attrib.clientPointer = clientPointer;
        attrib.pointerSource = source;
        attrib.usesVertexBinding = false;
        attrib.bindingIndex = 0;
        attrib.relativeOffset = 0;
        assignBufferGeneration(attrib, vboId);
    }

    private static void copyPointer(Attrib target, Attrib source, PointerSource pointerSource) {
        final boolean enabled = target.enabled;
        final int divisor = target.divisor;
        target.copyPointerFrom(source);
        target.enabled = enabled;
        target.divisor = divisor;
        target.pointerSource = pointerSource;
    }

    private void assignBufferGeneration(Attrib attrib, int buffer) {
        if (buffer == 0) {
            attrib.bufferShareGroupId = 0L;
            attrib.bufferGeneration = 0L;
            return;
        }
        attrib.bufferShareGroupId = shareGroupId;
        attrib.bufferGeneration = bufferGenerations.current(buffer);
    }

    public static void setEnabled(int index, boolean enabled) {
        GLStateManager.getVertexAttribState().setEnabledForContext(index, enabled);
    }

    public void setEnabledForContext(int index, boolean enabled) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        currentGeneric[index].enabled = enabled;
        current[index].enabled = enabled;
        recomputeClientSideCount();
    }

    /** Enables or disables the conventional client array independently of the generic array. */
    public static void setConventionalEnabled(int index, boolean enabled) {
        GLStateManager.getVertexAttribState().setConventionalEnabledForContext(index, enabled);
    }

    public void setConventionalEnabledForContext(int index, boolean enabled) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        currentConventional[index].enabled = enabled;
        current[index].enabled = enabled;
        recomputeClientSideCount();
    }

    /** Captures generic per-attribute instancing divisor state. */
    public static void setDivisor(int index, int divisor) {
        GLStateManager.getVertexAttribState().setDivisorForContext(index, divisor);
    }

    public void setDivisorForContext(int index, int divisor) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final Attrib attrib = currentGeneric[index];
        if (attrib.usesVertexBinding) {
            setVertexBindingDivisorForContext(attrib.bindingIndex, divisor);
        } else {
            attrib.divisor = divisor;
        }
        current[index].divisor = divisor;
    }

    /** Captures the instance divisor attached to one modern vertex-buffer binding. */
    public static void setVertexBindingDivisor(int bindingIndex, int divisor) {
        GLStateManager.getVertexAttribState().setVertexBindingDivisorForContext(bindingIndex, divisor);
    }

    public void setVertexBindingDivisorForContext(int bindingIndex, int divisor) {
        if (bindingIndex < 0 || bindingIndex >= MAX_VERTEX_BINDINGS) return;
        final Binding binding = currentBindings[bindingIndex];
        binding.configured = true;
        binding.bufferDivisor = divisor;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            final Attrib attrib = currentGeneric[i];
            if (attrib.usesVertexBinding && attrib.bindingIndex == bindingIndex) {
                attrib.divisor = divisor;
                current[i].divisor = divisor;
            }
        }
    }

    /** Captures GL 4.3 vertex-buffer binding state on the current VAO. */
    public static void bindVertexBuffer(int bindingIndex, int buffer, long offset, int stride) {
        GLStateManager.getVertexAttribState().bindVertexBufferForContext(bindingIndex, buffer, offset, stride);
    }

    public void bindVertexBufferForContext(int bindingIndex, int buffer, long offset, int stride) {
        if (bindingIndex < 0 || bindingIndex >= MAX_VERTEX_BINDINGS) return;
        final Binding binding = currentBindings[bindingIndex];
        binding.configured = true;
        binding.buffer = buffer;
        binding.offset = offset;
        binding.stride = stride;
        assignBufferGeneration(binding, buffer);
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            final Attrib attrib = currentGeneric[i];
            if (attrib.usesVertexBinding && attrib.bindingIndex == bindingIndex) {
                applyBinding(attrib, binding);
                if (current[i].pointerSource == PointerSource.GENERIC) {
                    copyPointer(current[i], attrib, PointerSource.GENERIC);
                    current[i].divisor = attrib.divisor;
                }
            }
        }
        recomputeClientSideCount();
    }

    /** Captures the floating-point format attached to one generic attribute. */
    public static void setFormat(int attribIndex, int size, int type, boolean normalized, int relativeOffset) {
        GLStateManager.getVertexAttribState().setFormatForContext(attribIndex, size, type, normalized, false, relativeOffset);
    }

    /** Captures the integer format attached to one generic attribute. */
    public static void setIntegerFormat(int attribIndex, int size, int type, int relativeOffset) {
        GLStateManager.getVertexAttribState().setFormatForContext(attribIndex, size, type, false, true, relativeOffset);
    }

    public void setFormatForContext(int attribIndex, int size, int type, boolean normalized, boolean integer, int relativeOffset) {
        if (attribIndex < 0 || attribIndex >= MAX_ATTRIBS) return;
        final Attrib attrib = currentGeneric[attribIndex];
        attrib.size = size;
        attrib.type = type;
        attrib.normalized = normalized;
        attrib.integer = integer;
        attrib.relativeOffset = relativeOffset;
        attrib.usesVertexBinding = true;
        currentBindings[attrib.bindingIndex].configured = true;
        applyBinding(attrib, currentBindings[attrib.bindingIndex]);
        copyPointer(current[attribIndex], attrib, PointerSource.GENERIC);
        current[attribIndex].divisor = attrib.divisor;
        recomputeClientSideCount();
    }

    /** Selects the vertex-buffer binding used by a generic attribute. */
    public static void setBinding(int attribIndex, int bindingIndex) {
        GLStateManager.getVertexAttribState().setBindingForContext(attribIndex, bindingIndex);
    }

    public void setBindingForContext(int attribIndex, int bindingIndex) {
        if (attribIndex < 0 || attribIndex >= MAX_ATTRIBS || bindingIndex < 0 || bindingIndex >= MAX_VERTEX_BINDINGS) return;
        final Attrib attrib = currentGeneric[attribIndex];
        attrib.bindingIndex = bindingIndex;
        attrib.usesVertexBinding = true;
        currentBindings[bindingIndex].configured = true;
        applyBinding(attrib, currentBindings[bindingIndex]);
        copyPointer(current[attribIndex], attrib, PointerSource.GENERIC);
        current[attribIndex].divisor = attrib.divisor;
        recomputeClientSideCount();
    }

    private static void applyBinding(Attrib attrib, Binding binding) {
        attrib.vboId = binding.buffer;
        attrib.offset = binding.offset + attrib.relativeOffset;
        attrib.stride = binding.stride;
        attrib.divisor = binding.bufferDivisor;
        attrib.clientPointer = null;
        attrib.bufferShareGroupId = binding.bufferShareGroupId;
        attrib.bufferGeneration = binding.bufferGeneration;
    }

    private void assignBufferGeneration(Binding binding, int buffer) {
        if (buffer == 0) {
            binding.bufferShareGroupId = 0L;
            binding.bufferGeneration = 0L;
            return;
        }
        binding.bufferShareGroupId = shareGroupId;
        binding.bufferGeneration = bufferGenerations.current(buffer);
    }

    public static Attrib get(int index) {
        return GLStateManager.getVertexAttribState().getAttrib(index);
    }

    public Attrib getAttrib(int index) {
        return current[index];
    }

    /** Returns the generic API definition independently of its current effective driver pointer. */
    public Attrib getGenericAttrib(int index) {
        return currentGeneric[index];
    }

    /** Returns the conventional client-array definition independently of generic arrays. */
    public Attrib getConventionalAttrib(int index) {
        return currentConventional[index];
    }

    /**
     * Reports whether the attribute at {@code location} is currently enabled on the bound VAO
     * without requiring {@link #init} to have run (returns {@code false} when no VAO state exists
     * yet). Used to detect whether a draw supplies per-vertex data for a slot — e.g. the extended
     * multi-texture UV slots 2/3 (issue #175) — so the FFP vertex shader can source its varying
     * from the attribute instead of a per-draw constant.
     */
    public static boolean isAttribEnabled(int location) {
        return GLStateManager.getVertexAttribState().isAttribEnabledForContext(location);
    }

    public boolean isAttribEnabledForContext(int location) {
        if (current == null || location < 0 || location >= MAX_ATTRIBS) {
            return false;
        }
        return current[location].enabled;
    }

    /**
     * Computes how many leading bytes of {@code a.clientPointer} a draw over vertices
     * [first, first + count) can actually read. The captured pointer deliberately spans the
     * whole underlying allocation (mods like HBM-CE mutate the Java limit after setting the
     * pointer), so the Java limit is never consulted; only the draw's first/count may narrow
     * the range. The last read byte of the final vertex is (first + count - 1) * stride +
     * vertexSize - 1, which stays correct even for stride smaller than the vertex size.
     * Negative strides read backwards from the pointer, so they fall back to the full
     * captured range. The result is always clamped to the captured allocation.
     */
    public static int computeUploadLength(Attrib a, int first, int count) {
        final int remaining = a.clientPointer.remaining();
        if (count <= 0) return 0;
        if (a.stride < 0) return remaining;
        final int stride = a.stride > 0 ? a.stride : a.size * a.typeSizeBytes();
        final long lastByteExclusive = (long) (first + count - 1) * stride + (long) a.size * a.typeSizeBytes();
        return (int) Math.min(remaining, lastByteExclusive);
    }

    /**
     * Captures the native pointer address without treating the Java buffer limit as its GL
     * allocation boundary. HBM reuses a BufferBuilder allocation and changes its limit between
     * uploads; OpenGL client pointers remain valid for the allocation range.
     */
    private ByteBuffer captureClientPointer(ByteBuffer pointer) {
        final int position = pointer.position();
        final int capacity = pointer.capacity() - position;
        return MemoryUtilities.memByteBuffer(MemoryUtilities.memAddress(pointer), capacity).order(ByteOrder.nativeOrder());
    }

    private static ByteBuffer duplicateClientPointer(ByteBuffer pointer) {
        return pointer == null ? null : pointer.duplicate().order(pointer.order());
    }



    private void recomputeClientSideCount() {
        int count = 0;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            if (current[i].isClientSide()) count++;
        }
        clientSideEnabledCount = count;
    }

    public static boolean hasVBOBoundAttrib() {
        return GLStateManager.getVertexAttribState().hasVBOBoundAttribForContext();
    }

    public boolean hasVBOBoundAttribForContext() {
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            if (current[i].enabled && current[i].vboId != 0) return true;
        }
        return false;
    }

    /**
     * Returns true if any currently enabled vertex attribute was registered without a VBO
     * (i.e. as a client-side pointer). In core profile, such attribs are treated as null
     * offsets into a non-existent buffer, causing a native crash at draw time.
     */
    public static boolean hasAnyClientSideEnabledAttrib() {
        return GLStateManager.getVertexAttribState().hasAnyClientSideEnabledAttribForContext();
    }

    public boolean hasAnyClientSideEnabledAttribForContext() {
        return clientSideEnabledCount > 0;
    }

    /**
     * Computes the FFP vertex-format flags from the attributes actually enabled on the
     * currently bound VAO. This is the authoritative source both for draws fed by
     * client-side arrays and for the raw GL draw entry points (glDrawElements/glDrawArrays
     * and friends): the format-based flag cache in ShaderManager only reflects the last
     * buffer-state setup (e.g. a VBO format), and client-state calls during third-party
     * model uploads (e.g. HBM-CE's glEnableClientState) can leak COLOR_BIT and friends into
     * the global flags without a matching VAO attribute. Either way the FFP shader would
     * declare attributes the draw does not provide and read the default (0,0,0,1) — a
     * POSITION-only draw such as the legacy end portal TESR renders black. Deriving from
     * the per-VAO attribute enablement is always consistent with the real GL state.
     */
    public static int currentClientArrayVertexFlags() {
        return GLStateManager.getVertexAttribState().currentClientArrayVertexFlagsForContext();
    }

    public int currentClientArrayVertexFlagsForContext() {
        if (current == null) {
            return 0;
        }
        int flags = 0;
        if (getAttrib(Usage.COLOR.getAttributeLocation()).enabled) flags |= VertexFlags.COLOR_BIT;
        if (getAttrib(Usage.NORMAL.getAttributeLocation()).enabled) flags |= VertexFlags.NORMAL_BIT;
        if (getAttrib(Usage.PRIMARY_UV.getAttributeLocation()).enabled) flags |= VertexFlags.TEXTURE_BIT;
        if (getAttrib(Usage.SECONDARY_UV.getAttributeLocation()).enabled) flags |= VertexFlags.BRIGHTNESS_BIT;
        return flags;
    }

    /** Captures all array representations and the VAO/buffer lifetime owning them. */
    public Snapshot snapshot(GLContextIdentity identity, int vao, int arrayBuffer, int elementArrayBuffer, int clientTextureUnit) {
        if (current == null) {
            throw new IllegalStateException("Vertex-array state has not been initialized for this context");
        }
        if (identity.shareGroupId() != shareGroupId) {
            throw new IllegalArgumentException("Vertex-array model share group does not match its context identity");
        }
        boolean hasVertexBindingApi = false;
        for (Attrib attrib : currentGeneric) {
            hasVertexBindingApi |= attrib.usesVertexBinding;
        }
        for (Binding binding : currentBindings) {
            hasVertexBindingApi |= binding.configured;
        }
        return new Snapshot(
            identity,
            vao,
            vertexArrayGeneration(vao),
            arrayBuffer,
            bufferGenerations.current(arrayBuffer),
            elementArrayBuffer,
            bufferGenerations.current(elementArrayBuffer),
            clientTextureUnit,
            copyAttribs(currentGeneric),
            copyAttribs(currentConventional),
            copyAttribs(current),
            copyBindings(currentBindings),
            hasVertexBindingApi
        );
    }

    /** Replays the captured VAO state to the driver without reviving deleted buffers or VAOs. */
    public boolean restoreDriver(Snapshot snapshot) {
        final GLContextIdentity currentIdentity = GLStateManager.currentContextIdentity();
        if (!snapshot.identity.equals(currentIdentity)) {
            throw new IllegalStateException("Client vertex-array snapshots cannot move between GL contexts");
        }
        if (!isVertexArrayGenerationCurrent(snapshot.vao, snapshot.vaoGeneration)) {
            return false;
        }

        GLStateManager.glBindVertexArray(snapshot.vao);
        if (snapshot.hasVertexBindingApi) {
            for (int i = 0; i < snapshot.bindings.length; i++) {
                final Binding binding = snapshot.bindings[i];
                if (!binding.configured) {
                    continue;
                }
                final boolean live = binding.buffer == 0 || bufferGenerations.isCurrent(
                    binding.buffer, binding.bufferGeneration);
                GLStateManager.glBindVertexBuffer(
                    i,
                    live ? binding.buffer : 0,
                    live ? binding.offset : 0L,
                    live ? binding.stride : 0
                );
                GLStateManager.glVertexBindingDivisor(i, binding.bufferDivisor);
            }
            for (int i = 0; i < MAX_ATTRIBS; i++) {
                final Attrib generic = snapshot.generic[i];
                if (!generic.usesVertexBinding) {
                    continue;
                }
                if (generic.integer) {
                    GLStateManager.glVertexAttribIFormat(i, generic.size, generic.type, generic.relativeOffset);
                } else {
                    GLStateManager.glVertexAttribFormat(i, generic.size, generic.type, generic.normalized, generic.relativeOffset);
                }
                GLStateManager.glVertexAttribBinding(i, generic.bindingIndex);
            }
        }
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            final Attrib generic = snapshot.generic[i];
            final Attrib effective = snapshot.effective[i];
            final boolean liveBuffer = isBufferReferenceCurrent(effective);
            if (effective.usesVertexBinding) {
                // Its pointer is represented by the restored vertex-binding record above.
            } else if (effective.clientPointer == null && effective.vboId != 0 && liveBuffer) {
                GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, effective.vboId);
                if (effective.integer) {
                    GLStateManager.glVertexAttribIPointer(
                        i, effective.size, effective.type, effective.stride, effective.offset);
                } else {
                    GLStateManager.glVertexAttribPointer(
                        i, effective.size, effective.type, effective.normalized, effective.stride, effective.offset);
                }
            }

            if (!effective.usesVertexBinding) {
                GLStateManager.glVertexAttribDivisor(i, effective.divisor);
            }
            if (effective.enabled && liveBuffer) {
                GLStateManager.glEnableVertexAttribArray(i);
            } else {
                GLStateManager.glDisableVertexAttribArray(i);
            }
        }

        final boolean liveElementBuffer = snapshot.elementArrayBuffer == 0 || bufferGenerations.isCurrent(
            snapshot.elementArrayBuffer, snapshot.elementArrayBufferGeneration);
        GLStateManager.glBindBuffer(
            GL15.GL_ELEMENT_ARRAY_BUFFER, liveElementBuffer ? snapshot.elementArrayBuffer : 0);
        final boolean liveArrayBuffer = snapshot.arrayBuffer == 0 || bufferGenerations.isCurrent(
            snapshot.arrayBuffer, snapshot.arrayBufferGeneration);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, liveArrayBuffer ? snapshot.arrayBuffer : 0);
        restoreModel(snapshot, false);
        return true;
    }

    /** Returns whether a captured buffer reference still names the same share-group object. */
    public boolean isBufferReferenceCurrent(Attrib attrib) {
        return attrib.vboId == 0 || attrib.bufferShareGroupId == shareGroupId
            && bufferGenerations.isCurrent(attrib.vboId, attrib.bufferGeneration);
    }

    /** Restores the captured software arrays after the driver has been replayed. */
    public void restoreModel(Snapshot snapshot, boolean keepCurrentVAO) {
        final int vao = keepCurrentVAO ? GLStateManager.getBoundVAO() : snapshot.vao;
        if (!isVertexArrayGenerationCurrent(vao, snapshot.vaoGeneration)) {
            throw new IllegalStateException("Cannot restore vertex arrays into a deleted or recycled VAO");
        }
        bindVertexArray(vao);
        copyAttribArray(currentGeneric, snapshot.generic);
        copyAttribArray(currentConventional, snapshot.conventional);
        copyAttribArray(current, snapshot.effective);
        copyBindingArray(currentBindings, snapshot.bindings);
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            if (!isBufferReferenceCurrent(currentGeneric[i])) {
                currentGeneric[i].clearBufferReference();
            }
            if (!isBufferReferenceCurrent(currentConventional[i])) {
                currentConventional[i].clearBufferReference();
            }
            if (!isBufferReferenceCurrent(current[i])) {
                current[i].clearBufferReference();
                current[i].enabled = false;
            }
        }
        for (Binding binding : currentBindings) {
            if (binding.buffer != 0 && (binding.bufferShareGroupId != shareGroupId
                || !bufferGenerations.isCurrent(binding.buffer, binding.bufferGeneration))) {
                binding.clearBufferReference();
            }
        }
        recomputeClientSideCount();
    }

    private static Attrib[] copyAttribs(Attrib[] source) {
        final Attrib[] copy = new Attrib[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].copy();
        }
        return copy;
    }

    private static void copyAttribArray(Attrib[] target, Attrib[] source) {
        for (int i = 0; i < target.length; i++) {
            target[i].copyFrom(source[i]);
        }
    }

    private static Binding[] copyBindings(Binding[] source) {
        final Binding[] copy = new Binding[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i].copy();
        }
        return copy;
    }

    private static void copyBindingArray(Binding[] target, Binding[] source) {
        for (int i = 0; i < target.length; i++) {
            target[i].copyFrom(source[i]);
        }
    }

    /** Deep value snapshot used by GL_CLIENT_VERTEX_ARRAY_BIT scopes. */
    public static final class Snapshot {
        private final GLContextIdentity identity;
        private final int vao;
        private final long vaoGeneration;
        private final int arrayBuffer;
        private final long arrayBufferGeneration;
        private final int elementArrayBuffer;
        private final long elementArrayBufferGeneration;
        private final int clientTextureUnit;
        private final Attrib[] generic;
        private final Attrib[] conventional;
        private final Attrib[] effective;
        private final Binding[] bindings;
        private final boolean hasVertexBindingApi;

        private Snapshot(
            GLContextIdentity identity,
            int vao,
            long vaoGeneration,
            int arrayBuffer,
            long arrayBufferGeneration,
            int elementArrayBuffer,
            long elementArrayBufferGeneration,
            int clientTextureUnit,
            Attrib[] generic,
            Attrib[] conventional,
            Attrib[] effective,
            Binding[] bindings,
            boolean hasVertexBindingApi
        ) {
            this.identity = identity;
            this.vao = vao;
            this.vaoGeneration = vaoGeneration;
            this.arrayBuffer = arrayBuffer;
            this.arrayBufferGeneration = arrayBufferGeneration;
            this.elementArrayBuffer = elementArrayBuffer;
            this.elementArrayBufferGeneration = elementArrayBufferGeneration;
            this.clientTextureUnit = clientTextureUnit;
            this.generic = generic;
            this.conventional = conventional;
            this.effective = effective;
            this.bindings = bindings;
            this.hasVertexBindingApi = hasVertexBindingApi;
        }

        public GLContextIdentity identity() { return identity; }
        public int vao() { return vao; }
        public long vaoGeneration() { return vaoGeneration; }
        public int arrayBuffer() { return arrayBuffer; }
        public long arrayBufferGeneration() { return arrayBufferGeneration; }
        public int elementArrayBuffer() { return elementArrayBuffer; }
        public long elementArrayBufferGeneration() { return elementArrayBufferGeneration; }
        public int clientTextureUnit() { return clientTextureUnit; }
        public boolean hasVertexBindingApi() { return hasVertexBindingApi; }
    }

    private enum PointerSource { GENERIC, CONVENTIONAL }

    /** One VAO vertex-buffer binding used by the separate format/binding API. */
    public static final class Binding {
        public boolean configured;
        public int buffer;
        public long offset;
        public int stride;
        public int bufferDivisor;
        public long bufferShareGroupId;
        public long bufferGeneration;

        private Binding copy() {
            final Binding copy = new Binding();
            copy.copyFrom(this);
            return copy;
        }

        private void copyFrom(Binding source) {
            configured = source.configured;
            buffer = source.buffer;
            offset = source.offset;
            stride = source.stride;
            bufferDivisor = source.bufferDivisor;
            bufferShareGroupId = source.bufferShareGroupId;
            bufferGeneration = source.bufferGeneration;
        }

        private void clearBufferReference() {
            buffer = 0;
            offset = 0L;
            stride = 0;
            bufferShareGroupId = 0L;
            bufferGeneration = 0L;
        }
    }

    public static class Attrib {
        public boolean enabled;
        public boolean integer;
        public int size;
        public int type;
        public boolean normalized;
        public int stride;
        public long offset;
        public int vboId;
        public ByteBuffer clientPointer;
        public int divisor;
        public int bindingIndex;
        public int relativeOffset;
        public boolean usesVertexBinding;
        public long bufferShareGroupId;
        public long bufferGeneration;
        private PointerSource pointerSource = PointerSource.GENERIC;

        public boolean isClientSide() {
            return enabled && clientPointer != null;
        }

        public void reset() {
            enabled = false;
            integer = false;
            size = 0;
            type = 0;
            normalized = false;
            stride = 0;
            offset = 0;
            vboId = 0;
            clientPointer = null;
            divisor = 0;
            bindingIndex = 0;
            relativeOffset = 0;
            usesVertexBinding = false;
            bufferShareGroupId = 0L;
            bufferGeneration = 0L;
            pointerSource = PointerSource.GENERIC;
        }

        private Attrib copy() {
            final Attrib copy = new Attrib();
            copy.copyFrom(this);
            return copy;
        }

        private void copyFrom(Attrib source) {
            enabled = source.enabled;
            integer = source.integer;
            size = source.size;
            type = source.type;
            normalized = source.normalized;
            stride = source.stride;
            offset = source.offset;
            vboId = source.vboId;
            clientPointer = duplicateClientPointer(source.clientPointer);
            divisor = source.divisor;
            bindingIndex = source.bindingIndex;
            relativeOffset = source.relativeOffset;
            usesVertexBinding = source.usesVertexBinding;
            bufferShareGroupId = source.bufferShareGroupId;
            bufferGeneration = source.bufferGeneration;
            pointerSource = source.pointerSource;
        }

        private void copyPointerFrom(Attrib source) {
            integer = source.integer;
            size = source.size;
            type = source.type;
            normalized = source.normalized;
            stride = source.stride;
            offset = source.offset;
            vboId = source.vboId;
            clientPointer = duplicateClientPointer(source.clientPointer);
            bindingIndex = source.bindingIndex;
            relativeOffset = source.relativeOffset;
            usesVertexBinding = source.usesVertexBinding;
            bufferShareGroupId = source.bufferShareGroupId;
            bufferGeneration = source.bufferGeneration;
            pointerSource = source.pointerSource;
        }

        private void clearBufferReference() {
            vboId = 0;
            offset = 0L;
            clientPointer = null;
            bufferShareGroupId = 0L;
            bufferGeneration = 0L;
            enabled = false;
        }

        public int effectiveStride() {
            return (stride != 0) ? stride : size * typeSizeBytes();
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
                    final int v = buf.getInt(base + component * 4);
                    yield normalized ? v / (float) Integer.MAX_VALUE : (float) v;
                }
                case GL11.GL_UNSIGNED_INT -> {
                    final int v = buf.getInt(base + component * 4);
                    yield normalized ? (v & 0xFFFFFFFFL) / (float) 0xFFFFFFFFL : (float) (v & 0xFFFFFFFFL);
                }
                case GL11.GL_SHORT -> {
                    final short v = buf.getShort(base + component * 2);
                    yield normalized ? v / (float) Short.MAX_VALUE : (float) v;
                }
                case GL11.GL_UNSIGNED_SHORT -> {
                    final int v = buf.getShort(base + component * 2) & 0xFFFF;
                    yield normalized ? v / (float) 0xFFFF : (float) v;
                }
                case GL11.GL_BYTE -> {
                    final byte v = buf.get(base + component);
                    yield normalized ? v / 127.0f : (float) v;
                }
                case GL11.GL_UNSIGNED_BYTE -> {
                    final int v = buf.get(base + component) & 0xFF;
                    yield normalized ? v / 255.0f : (float) v;
                }
                default -> 0.0f;
            };
        }
    }
}
