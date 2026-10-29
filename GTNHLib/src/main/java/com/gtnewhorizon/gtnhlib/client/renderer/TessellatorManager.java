package com.gtnewhorizon.gtnhlib.client.renderer;

import com.google.common.annotations.Beta;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.IVertexArrayObject;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.VertexBufferType;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public final class TessellatorManager {
    public static final int DEFAULT_BUFFER_SIZE = 0x8000;
    private static final int DIRECT_TESSELLATOR_STACK_DEPTH = 16;

    // The capture stack is per-thread: a capture started on one thread (e.g. display
    // list recording on the client thread) must never intercept draws issued by
    // another thread (e.g. CustomLoadingScreen's splash renderer), otherwise the
    // foreign draw lands in the owner's tessellator and corrupts state.
    private static final ThreadLocal<DirectTessellator> mainInstance =
        ThreadLocal.withInitial(() -> new DirectTessellator(DEFAULT_BUFFER_SIZE, false));
    private static final ThreadLocal<CallbackTessellator> mainCallbackInstance =
        ThreadLocal.withInitial(() -> new CallbackTessellator(DEFAULT_BUFFER_SIZE, false));
    private static final ThreadLocal<DirectTessellator[]> directTessellators =
        ThreadLocal.withInitial(() -> new DirectTessellator[DIRECT_TESSELLATOR_STACK_DEPTH]);

    private static final ThreadLocal<Integer> directTessellatorIndex = ThreadLocal.withInitial(() -> -1);
    private static final ThreadLocal<Boolean> mainInstanceInStack = ThreadLocal.withInitial(() -> false);

    /** Serializes owned capture state transitions without reaching into another thread's capture stack. */
    private static final Object OWNED_CAPTURE_LOCK = new Object();

    /** Weak identity entries prevent a stopped CallbackTessellator token from being reactivated by the legacy API. */
    private static final Map<DirectTessellator, OwnedCaptureState> ownedCaptures = new WeakHashMap<>();

    private TessellatorManager() {
    }

    private static DirectTessellator getDirectTessellator() {
        return directTessellators.get()[directTessellatorIndex.get()];
    }

    private static boolean hasDirectTessellator() {
        return directTessellatorIndex.get() != -1;
    }

    private static boolean isMainTessellator(DirectTessellator tessellator) {
        return tessellator == mainInstance.get() || tessellator == mainCallbackInstance.get();
    }

    private static void setDirectTessellator(DirectTessellator tessellator) {
        synchronized (OWNED_CAPTURE_LOCK) {
            if (ownedCaptures.containsKey(tessellator)) {
                throw new IllegalArgumentException("Owned tessellator tokens must be resumed with TessellatorManager.resume");
            }
            pushDirectTessellator(tessellator);
        }
    }

    private static void pushDirectTessellator(DirectTessellator tessellator) {
        pushDirectTessellator(tessellator, isMainTessellator(tessellator));
    }

    private static void pushOwnedTessellator(DirectTessellator tessellator) {
        pushDirectTessellator(tessellator, false);
    }

    private static void pushDirectTessellator(DirectTessellator tessellator, boolean mainTessellator) {
        final int index = directTessellatorIndex.get() + 1;
        if (index >= DIRECT_TESSELLATOR_STACK_DEPTH) {
            throw new IllegalStateException("DirectTessellator stack overflow");
        }
        mainInstanceInStack.set(mainInstanceInStack.get() || mainTessellator);
        directTessellators.get()[index] = tessellator;
        directTessellatorIndex.set(index);
    }

    /**
     * Starts a uniquely-owned draw capture whose token can be suspended, moved to another thread, or canceled
     * without disturbing the legacy LIFO captures on the calling thread.
     *
     * @param callback receives draw batches emitted into the owned tessellator
     * @return the exact owned tessellator token that must be supplied to {@link #suspend(DirectTessellator)},
     *         {@link #resume(DirectTessellator)}, {@link #stop(DirectTessellator)}, or
     *         {@link #cancel(DirectTessellator)}
     */
    @Beta
    public static DirectTessellator startOwned(DirectDrawCallback callback) {
        Objects.requireNonNull(callback, "callback");
        final CallbackTessellator tessellator = new CallbackTessellator(DEFAULT_BUFFER_SIZE);
        tessellator.setDrawCallback(callback);
        synchronized (OWNED_CAPTURE_LOCK) {
            try {
                pushOwnedTessellator(tessellator);
                ownedCaptures.put(tessellator, new OwnedCaptureState(Thread.currentThread()));
            } catch (RuntimeException | Error exception) {
                final int index = findCapture(tessellator);
                if (index >= 0) {
                    removeCaptureAt(index);
                }
                ownedCaptures.remove(tessellator);
                tessellator.delete();
                throw exception;
            }
        }
        return tessellator;
    }

    /** Suspends the exact owned capture, removing it from any stack position on its owner thread without reset. */
    @Beta
    public static void suspend(DirectTessellator token) {
        Objects.requireNonNull(token, "token");
        synchronized (OWNED_CAPTURE_LOCK) {
            final OwnedCaptureState state = requireOwnedCapture(token);
            if (state.stopped) {
                throw new IllegalStateException("Owned tessellator capture is already stopped");
            }
            requireCurrentOwner(state);
            final int index = findCapture(token);
            if (index < 0) {
                throw new IllegalStateException("Owned tessellator capture is missing from its owner thread stack");
            }
            removeCaptureAt(index);
            state.ownerThread = null;
        }
    }

    /** Resumes a suspended token by attaching it to the calling thread's capture stack. */
    @Beta
    public static void resume(DirectTessellator token) {
        Objects.requireNonNull(token, "token");
        synchronized (OWNED_CAPTURE_LOCK) {
            final OwnedCaptureState state = requireOwnedCapture(token);
            if (state.stopped) {
                throw new IllegalStateException("Owned tessellator capture is already stopped");
            }
            if (state.ownerThread != null) {
                throw new IllegalStateException("Owned tessellator capture must be suspended before it can move");
            }
            pushOwnedTessellator(token);
            state.ownerThread = Thread.currentThread();
        }
    }

    /** Stops the exact owned capture and releases its native buffer, including when it is not at stack top. */
    @Beta
    public static void stop(DirectTessellator token) {
        finishOwnedCapture(token);
    }

    /** Cancels the exact owned capture and releases its native buffer, including when it is suspended. */
    @Beta
    public static void cancel(DirectTessellator token) {
        finishOwnedCapture(token);
    }

    private static void finishOwnedCapture(DirectTessellator token) {
        Objects.requireNonNull(token, "token");
        synchronized (OWNED_CAPTURE_LOCK) {
            final OwnedCaptureState state = requireOwnedCapture(token);
            if (state.stopped) {
                return;
            }
            if (state.ownerThread != null) {
                requireCurrentOwner(state);
                final int index = findCapture(token);
                if (index < 0) {
                    throw new IllegalStateException("Owned tessellator capture is missing from its owner thread stack");
                }
                removeCaptureAt(index);
            }
            state.ownerThread = null;
            state.stopped = true;
        }
        releaseOwnedCapture(token);
    }

    private static OwnedCaptureState requireOwnedCapture(DirectTessellator token) {
        final OwnedCaptureState state = ownedCaptures.get(token);
        if (state == null) {
            throw new IllegalArgumentException("Tessellator is not an active owned capture token");
        }
        return state;
    }

    private static void requireCurrentOwner(OwnedCaptureState state) {
        if (state.ownerThread != Thread.currentThread()) {
            throw new IllegalStateException("Only the owned capture's current thread may change its active stack entry");
        }
    }

    private static int findCapture(DirectTessellator token) {
        final DirectTessellator[] stack = directTessellators.get();
        for (int index = directTessellatorIndex.get(); index >= 0; index--) {
            if (stack[index] == token) {
                return index;
            }
        }
        return -1;
    }

    private static void removeCaptureAt(int index) {
        final DirectTessellator[] stack = directTessellators.get();
        final int top = directTessellatorIndex.get();
        for (int cursor = index; cursor < top; cursor++) {
            stack[cursor] = stack[cursor + 1];
        }
        stack[top] = null;
        directTessellatorIndex.set(top - 1);
    }

    private static void releaseOwnedCapture(DirectTessellator token) {
        if (token instanceof CallbackTessellator callbackTessellator) {
            callbackTessellator.setDrawCallback(null);
        }
        token.onRemovedFromStack();
    }

    /** Mutable state for an owned token; terminal entries use weak keys so stale handles cannot be reactivated. */
    private static final class OwnedCaptureState {
        private Thread ownerThread;
        private boolean stopped;

        private OwnedCaptureState(Thread ownerThread) {
            this.ownerThread = ownerThread;
        }
    }

    public static DirectTessellator startCapturingDirect() {
        if (!mainInstanceInStack.get()) {
            setDirectTessellator(mainInstance.get());
            return mainInstance.get();
        }
        final DirectTessellator tessellator = new DirectTessellator(DEFAULT_BUFFER_SIZE);
        setDirectTessellator(tessellator);
        return tessellator;
    }

    public static DirectTessellator startCapturingDirect(int capacity) {
        if (!mainInstanceInStack.get() && DEFAULT_BUFFER_SIZE >= capacity) {
            setDirectTessellator(mainInstance.get());
            return mainInstance.get();
        }
        final DirectTessellator tessellator = new DirectTessellator(capacity);
        setDirectTessellator(tessellator);
        return tessellator;
    }

    public static DirectTessellator startCapturingDirect(VertexFormat format) {
        final DirectTessellator tessellator = startCapturingDirect();
        tessellator.setVertexFormat(format);
        return tessellator;
    }

    @Beta
    public static CallbackTessellator startCapturingDirect(DirectDrawCallback callback) {
        if (!mainInstanceInStack.get()) {
            final CallbackTessellator tessellator = mainCallbackInstance.get();
            tessellator.setDrawCallback(callback);
            setDirectTessellator(tessellator);
            return tessellator;
        }
        final CallbackTessellator tessellator = new CallbackTessellator(DEFAULT_BUFFER_SIZE);
        tessellator.setDrawCallback(callback);
        setDirectTessellator(tessellator);
        return tessellator;
    }

    public static void startCapturingDirect(DirectTessellator tessellator) {
        setDirectTessellator(tessellator);
    }

    public static void stopCapturingDirect() {
        final DirectTessellator tessellator;
        final boolean owned;
        synchronized (OWNED_CAPTURE_LOCK) {
            if (!hasDirectTessellator()) {
                throw new IllegalStateException("Tried to stop capturing when not capturing!");
            }
            tessellator = getDirectTessellator();
            final OwnedCaptureState ownedState = ownedCaptures.get(tessellator);
            owned = ownedState != null;
            if (ownedState != null) {
                if (ownedState.stopped) {
                    throw new IllegalStateException("Stopped owned tessellator capture remained on the capture stack");
                }
                requireCurrentOwner(ownedState);
                ownedState.ownerThread = null;
                ownedState.stopped = true;
            }
            final int top = directTessellatorIndex.get();
            removeCaptureAt(top);
            if (isMainTessellator(tessellator)) {
                mainInstanceInStack.set(false);
            }
        }
        if (owned) {
            releaseOwnedCapture(tessellator);
        } else {
            tessellator.onRemovedFromStack();
        }
    }

    public static IVertexArrayObject stopCapturingDirectToVBO(VertexBufferType bufferType) {
        if (!hasDirectTessellator()) {
            throw new IllegalStateException("Tried to stop capturing when not capturing!");
        }
        final DirectTessellator tessellator = getDirectTessellator();
        final IVertexArrayObject vbo = tessellator.uploadToVBO(bufferType);
        stopCapturingDirect();
        return vbo;
    }

    public static boolean shouldInterceptDraw(Tessellator tess) {
        return tess instanceof ITessellatorInstance instance && instance.gtnhlib$isCompiling()
                || shouldInterceptBufferBuilderDraw();
    }

    public static boolean shouldInterceptBufferBuilderDraw() {
        return RuntimeOptionsBridge.allowDirectMemoryAccess() && hasDirectTessellator();
    }

    public static void interceptBufferBuilderDraw(BufferBuilder bufferBuilder) {
        if (!shouldInterceptBufferBuilderDraw()) {
            throw new IllegalStateException("interceptBufferBuilderDraw called without an active DirectTessellator");
        }

        final DirectTessellator tessellator = getDirectTessellator();
        copyBufferBuilderToDirect(bufferBuilder, tessellator);
        tessellator.draw();
        bufferBuilder.reset();
    }

    public static void cleanup() {
        while (hasDirectTessellator()) {
            stopCapturingDirect();
        }
    }

    private static void copyBufferBuilderToDirect(BufferBuilder bufferBuilder, DirectTessellator tessellator) {
        final net.minecraft.client.renderer.vertex.VertexFormat mcFormat = bufferBuilder.getVertexFormat();
        final VertexFormat directFormat = mapVertexFormat(mcFormat);
        tessellator.setVertexFormat(directFormat);
        tessellator.startDrawing(bufferBuilder.getDrawMode());

        final int vertexCount = bufferBuilder.getVertexCount();
        final int stride = mcFormat.getSize();

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            copyVertex(bufferBuilder.getByteBuffer(), mcFormat, stride, vertex, tessellator);
        }
    }

    private static VertexFormat mapVertexFormat(net.minecraft.client.renderer.vertex.VertexFormat format) {
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

        return VertexFlags.getFormat(hasTexture, hasColor, hasNormal, hasBrightness);
    }

    private static void copyVertex(
            java.nio.ByteBuffer buffer,
            net.minecraft.client.renderer.vertex.VertexFormat format,
            int stride,
            int vertex,
            DirectTessellator tessellator
    ) {
        float x = 0.0F;
        float y = 0.0F;
        float z = 0.0F;

        for (int elementIndex = 0; elementIndex < format.getElementCount(); elementIndex++) {
            final VertexFormatElement element = format.getElement(elementIndex);
            final int base = vertex * stride + format.getOffset(elementIndex);

            switch (element.getUsage()) {
                case POSITION -> {
                    x = readComponent(buffer, base, element, 0);
                    y = element.getElementCount() > 1 ? readComponent(buffer, base, element, 1) : 0.0F;
                    z = element.getElementCount() > 2 ? readComponent(buffer, base, element, 2) : 0.0F;
                }
                case COLOR -> tessellator.setPackedColorRaw(buffer.getInt(base));
                case NORMAL -> tessellator.setPackedNormalRaw(buffer.getInt(base));
                case UV -> {
                    if (element.getIndex() == 0) {
                        tessellator.setLastTextureUVRaw(
                                readComponent(buffer, base, element, 0),
                                element.getElementCount() > 1 ? readComponent(buffer, base, element, 1) : 0.0F);
                    } else if (element.getIndex() == 1) {
                        tessellator.setPackedBrightnessRaw(readPackedLight(buffer, base, element));
                    }
                }
                default -> {
                }
            }
        }

        tessellator.addVertex(x, y, z);
    }

    private static float readComponent(java.nio.ByteBuffer buffer, int base, VertexFormatElement element, int component) {
        final int offset = base + component * element.getType().getSize();

        return switch (element.getType()) {
            case FLOAT -> buffer.getFloat(offset);
            case BYTE -> buffer.get(offset) / 127.0F;
            case UBYTE -> (buffer.get(offset) & 0xFF) / 255.0F;
            case SHORT -> buffer.getShort(offset) / 32767.0F;
            case USHORT -> (buffer.getShort(offset) & 0xFFFF) / 65535.0F;
            case INT -> buffer.getInt(offset);
            case UINT -> (float) Integer.toUnsignedLong(buffer.getInt(offset));
        };
    }

    private static int readPackedLight(java.nio.ByteBuffer buffer, int base, VertexFormatElement element) {
        return switch (element.getType()) {
            case SHORT, USHORT -> {
                int s = buffer.getShort(base) & 0xFFFF;
                int t = element.getElementCount() > 1 ? buffer.getShort(base + 2) & 0xFFFF : 0;
                yield (t << 16) | s;
            }
            case FLOAT -> {
                int s = (int) buffer.getFloat(base);
                int t = element.getElementCount() > 1 ? (int) buffer.getFloat(base + 4) : 0;
                yield (t << 16) | (s & 0xFFFF);
            }
            default -> buffer.getInt(base);
        };
    }
}
