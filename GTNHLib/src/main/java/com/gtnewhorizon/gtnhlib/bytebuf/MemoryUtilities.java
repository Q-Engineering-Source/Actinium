/*
 * Copyright LWJGL. All rights reserved. License terms: https://www.lwjgl.org/license
 */
package com.gtnewhorizon.gtnhlib.bytebuf;

import static com.gtnewhorizon.gtnhlib.bytebuf.APIUtil.apiCheckAllocation;
import static com.gtnewhorizon.gtnhlib.bytebuf.APIUtil.apiGetBytes;
import static com.gtnewhorizon.gtnhlib.bytebuf.Checks.CHECKS;
import static com.gtnewhorizon.gtnhlib.bytebuf.Checks.DEBUG;
import static com.gtnewhorizon.gtnhlib.bytebuf.Checks.check;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.LazyInit.ALLOCATOR;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.LazyInit.ALLOCATOR_IMPL;
import static com.gtnewhorizon.gtnhlib.bytebuf.Pointer.BITS32;
import static com.gtnewhorizon.gtnhlib.bytebuf.Pointer.BITS64;
import static com.gtnewhorizon.gtnhlib.bytebuf.Pointer.CLONG_SIZE;
import static com.gtnewhorizon.gtnhlib.bytebuf.Pointer.POINTER_SHIFT;
import static java.lang.Character.isHighSurrogate;
import static java.lang.Character.toCodePoint;
import static java.lang.Math.min;

import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * This class provides functionality for managing native memory.
 *
 * <p>
 * Low-level native memory operations are delegated to LWJGL's maintained {@link MemoryUtil} implementation.
 * </p>
 *
 * <p>
 * Method names in this class are prefixed with {@code mem} to avoid ambiguities when used with static imports.
 * </p>
 *
 * <h3>Text encoding/decoding</h3>
 *
 * Three codecs are available, each with a different postfix:
 * <ul>
 * <li>UTF16 - Direct mapping of 2 bytes to Java char and vice versa</li>
 * <li>UTF8 - custom UTF-8 codec without intermediate allocations</li>
 * <li>ASCII - Not the original 7bit ASCII, but any character set with a single byte encoding (ISO 8859-1, Windows-1252,
 * etc.)</li>
 * </ul>
 *
 * <p>
 * The codec implementations do no codepoint validation, for improved performance. Therefore, if malformed input or
 * unmappable characters are expected, the JDK {@link CharsetEncoder}/{@link CharsetDecoder} classes should be used
 * instead. Methods in bindings that accept/return {@code CharSequence}/{@code String} also support {@code ByteBuffer},
 * so custom codecs can be used if necessary.
 * </p>
 */
public final class MemoryUtilities {

    /**
     * Returns true if the specified integer {@code value} is a power-of-two number.
     */
    public static boolean mathIsPoT(int value) {
        return Integer.bitCount(value) == 1;
    }

    /**
     * Rounds the specified integer {@code value} up to the next power-of-two number. The returned value will be equal
     * to {@code value} if it already is a power-of-two number.
     *
     * @param value the value to round-up. Must be a number between {@code 1} and <code>1 &lt;&lt; 30</code>.
     */
    public static int mathRoundPoT(int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    public static boolean mathHasZeroByte(int value) {
        return ((value - 0x01010101) & ~value & 0x80808080) != 0;
    }

    public static boolean mathHasZeroByte(long value) {
        return ((value - 0x0101010101010101L) & ~value & 0x8080808080808080L) != 0L;
    }

    public static boolean mathHasZeroShort(int value) {
        return ((value - 0x00010001) & ~value & 0x80008000) != 0;
    }

    public static boolean mathHasZeroShort(long value) {
        return ((value - 0x0001000100010001L) & ~value & 0x8000800080008000L) != 0L;
    }

    /** Alias for the null pointer address. */
    public static final long NULL = 0L;

    /** The memory page size, in bytes. This value is always a power-of-two. */
    public static final int PAGE_SIZE;

    /** The cache-line size, in bytes. This value is always a power-of-two. */
    public static final int CACHE_LINE_SIZE;

    static final int ARRAY_TLC_SIZE = 8192;

    static final ThreadLocal<byte[]> ARRAY_TLC_BYTE = ThreadLocal.withInitial(() -> new byte[ARRAY_TLC_SIZE]);
    static final ThreadLocal<char[]> ARRAY_TLC_CHAR = ThreadLocal.withInitial(() -> new char[ARRAY_TLC_SIZE]);

    static final ByteOrder NATIVE_ORDER = ByteOrder.nativeOrder();

    private static final Charset UTF16 = NATIVE_ORDER == ByteOrder.LITTLE_ENDIAN ? StandardCharsets.UTF_16LE
            : StandardCharsets.UTF_16BE;

    static {
        PAGE_SIZE = MemoryUtil.PAGE_SIZE;
        CACHE_LINE_SIZE = MemoryUtil.CACHE_LINE_SIZE;
    }

    static final class LazyInit {

        private LazyInit() {}

        static final MemoryAllocator ALLOCATOR_IMPL;
        static final MemoryAllocator ALLOCATOR;

        static {
            boolean debug = Boolean.getBoolean("org.lwjgl.util.DebugAllocator");

            ALLOCATOR_IMPL = MemoryManage.getInstance();
            ALLOCATOR = ALLOCATOR_IMPL;
        }
    }

    private MemoryUtilities() {}

    /**
     * The interface implemented by the memory allocator used by the explicit memory management API ({@link #memAlloc},
     * {@link #memFree}, etc).
     */
    public interface MemoryAllocator {

        /** Called by {@link MemoryUtilities#memAlloc}. */
        long malloc(long size);

        /** Called by {@link MemoryUtilities#memCalloc}. */
        long calloc(long num, long size);

        /** Called by {@link MemoryUtilities#memRealloc}. */
        long realloc(long ptr, long size);

        /** Called by {@link MemoryUtilities#memFree}. */
        void free(long ptr);
    }

    /**
     * Returns the {@link MemoryAllocator} instance used internally by the explicit memory management API
     * ({@link #memAlloc}, {@link #memFree}, etc).
     *
     * <p>
     * Allocations made through the returned instance will not be tracked for memory leaks, even if is enabled. This can
     * be useful for {@code static final} allocations that live throughout the application's lifetime and will never be
     * freed until the process is terminated. Normally such allocations would be reported as memory leaks by the debug
     * allocator.
     * </p>
     *
     * <p>
     * The expectation is that this method will rarely be used, so it does not have the {@code mem} prefix to avoid
     * pollution of auto-complete lists.
     * </p>
     */
    public static MemoryAllocator getAllocator() {
        return getAllocator(false);
    }

    /**
     * Returns the {@link MemoryAllocator} instance used internally by the explicit memory management API
     * ({@link #memAlloc}, {@link #memFree}, etc).
     *
     * @param tracked whether allocations will be tracked for memory leaks, if is enabled.
     */
    public static MemoryAllocator getAllocator(boolean tracked) {
        return tracked ? ALLOCATOR : ALLOCATOR_IMPL;
    }

    /**
     * Unsafe version of {@link #memAlloc}. May return {@link #NULL} if {@code size} is zero or the allocation failed.
     */
    public static long nmemAlloc(long size) {
        return ALLOCATOR.malloc(size);
    }

    /**
     * Unsafe version of {@link #memAlloc} that checks the returned pointer.
     *
     * @return a pointer to the memory block allocated by the function on success. This pointer will never be
     *         {@link #NULL}, even if {@code size} is zero.
     *
     * @throws OutOfMemoryError if the function failed to allocate the requested block of memory
     */
    public static long nmemAllocChecked(long size) {
        long address = nmemAlloc(size != 0 ? size : 1L);
        if (CHECKS && address == NULL) {
            throw new OutOfMemoryError();
        }
        return address;
    }

    private static long getAllocationSize(int elements, int elementShift) {
        return apiCheckAllocation(
                elements,
                Integer.toUnsignedLong(elements) << elementShift,
                BITS64 ? Long.MAX_VALUE : 0xFFFF_FFFFL);
    }

    /**
     * The standard C malloc function.
     *
     * <p>
     * Allocates a block of {@code size} bytes of memory, returning a pointer to the beginning of the block. The content
     * of the newly allocated block of memory is not initialized, remaining with indeterminate values.
     * </p>
     *
     * <p>
     * Memory allocated with this method must be freed with {@link #memFree}.
     * </p>
     *
     * @param size the size of the memory block to allocate, in bytes. If {@code size} is zero, the returned pointer
     *             shall not be dereferenced.
     *
     * @return on success, a pointer to the memory block allocated by the function
     *
     * @throws OutOfMemoryError if the function failed to allocate the requested block of memory
     */
    public static ByteBuffer memAlloc(int size) {
        return wrapBufferByte(nmemAllocChecked(size), size);
    }

    /**
     * ShortBuffer version of {@link #memAlloc}.
     *
     * @param size the number of short values to allocate.
     */
    public static ShortBuffer memAllocShort(int size) {
        return wrapBufferShort(nmemAllocChecked(getAllocationSize(size, 1)), size);
    }

    /**
     * IntBuffer version of {@link #memAlloc}.
     *
     * @param size the number of int values to allocate.
     */
    public static IntBuffer memAllocInt(int size) {
        return wrapBufferInt(nmemAllocChecked(getAllocationSize(size, 2)), size);
    }

    /**
     * FloatBuffer version of {@link #memAlloc}.
     *
     * @param size the number of float values to allocate.
     */
    public static FloatBuffer memAllocFloat(int size) {
        return wrapBufferFloat(nmemAllocChecked(getAllocationSize(size, 2)), size);
    }

    /**
     * LongBuffer version of {@link #memAlloc}.
     *
     * @param size the number of long values to allocate.
     */
    public static LongBuffer memAllocLong(int size) {
        return wrapBufferLong(nmemAllocChecked(getAllocationSize(size, 3)), size);
    }

    /**
     * DoubleBuffer version of {@link #memAlloc}.
     *
     * @param size the number of double values to allocate.
     */
    public static DoubleBuffer memAllocDouble(int size) {
        return wrapBufferDouble(nmemAllocChecked(getAllocationSize(size, 3)), size);
    }

    /**
     * PointerBuffer version of {@link #memAlloc}.
     *
     * @param size the number of pointer values to allocate.
     */
    public static PointerBuffer memAllocPointer(int size) {
        return PointerBuffer.create(memAlloc(Math.toIntExact(getAllocationSize(size, POINTER_SHIFT))));
    }

    /** Unsafe version of {@link #memFree}. */
    public static void nmemFree(long ptr) {
        ALLOCATOR.free(ptr);
    }

    /**
     * The standard C free function.
     *
     * <p>
     * A block of memory previously allocated by a call to {@link #memAlloc}, {@link #memCalloc} or {@link #memRealloc}
     * is deallocated, making it available again for further allocations.
     * </p>
     *
     * @param ptr pointer to a memory block previously allocated with {@link #memAlloc}, {@link #memCalloc} or
     *            {@link #memRealloc}. If {@code ptr} does not point to a block of memory allocated with the above
     *            functions, it causes undefined behavior. If {@code ptr} is a {@link #NULL} pointer, the function does
     *            nothing.
     */
    public static void memFree(@Nullable Buffer ptr) {
        if (ptr != null) {
            nmemFree(MemoryUtil.memAddress0(ptr));
        }
    }

    /** {@code ByteBuffer} version of {@link #memFree(Buffer)}. */
    public static void memFree(@Nullable ByteBuffer ptr) {
        if (ptr != null) {
            nmemFree(MemoryUtil.memAddress0(ptr));
        }
    }

    /** {@code ShortBuffer} version of {@link #memFree(Buffer)}. */
    public static void memFree(@Nullable ShortBuffer ptr) {
        if (ptr != null) {
            nmemFree(MemoryUtil.memAddress0(ptr));
        }
    }

    /** {@code CharBuffer} version of {@link #memFree(Buffer)}. */
    public static void memFree(@Nullable CharBuffer ptr) {
        if (ptr != null) {
            nmemFree(MemoryUtil.memAddress0(ptr));
        }
    }

    /** {@code IntBuffer} version of {@link #memFree(Buffer)}. */
    public static void memFree(@Nullable IntBuffer ptr) {
        if (ptr != null) {
            nmemFree(MemoryUtil.memAddress0(ptr));
        }
    }

    /** {@code LongBuffer} version of {@link #memFree(Buffer)}. */
    public static void memFree(@Nullable LongBuffer ptr) {
        if (ptr != null) {
            nmemFree(MemoryUtil.memAddress0(ptr));
        }
    }

    /** {@code FloatBuffer} version of {@link #memFree(Buffer)}. */
    public static void memFree(@Nullable FloatBuffer ptr) {
        if (ptr != null) {
            nmemFree(MemoryUtil.memAddress0(ptr));
        }
    }

    /** {@code DoubleBuffer} version of {@link #memFree(Buffer)}. */
    public static void memFree(@Nullable DoubleBuffer ptr) {
        if (ptr != null) {
            nmemFree(MemoryUtil.memAddress0(ptr));
        }
    }

    /**
     * Unsafe version of {@link #memCalloc}. May return {@link #NULL} if {@code num} or {@code size} are zero or the
     * allocation failed.
     */
    public static long nmemCalloc(long num, long size) {
        return ALLOCATOR.calloc(num, size);
    }

    /**
     * Unsafe version of {@link #memCalloc} that checks the returned pointer.
     *
     * @return a pointer to the memory block allocated by the function on success. This pointer will never be
     *         {@link #NULL}, even if {@code num} or {@code size} are zero.
     *
     * @throws OutOfMemoryError if the function failed to allocate the requested block of memory
     */
    public static long nmemCallocChecked(long num, long size) {
        if (num == 0L || size == 0L) {
            num = 1L;
            size = 1L;
        }

        long address = nmemCalloc(num, size);
        if (CHECKS && address == NULL) {
            throw new OutOfMemoryError();
        }
        return address;
    }

    /**
     * The standard C calloc function.
     *
     * <p>
     * Allocates a block of memory for an array of {@code num} elements, each of them {@code size} bytes long, and
     * initializes all its bits to zero. The effective result is the allocation of a zero-initialized memory block of
     * {@code (num*size)} bytes.
     * </p>
     *
     * <p>
     * Memory allocated with this method must be freed with {@link #memFree}.
     * </p>
     *
     * @param size the size of each element. If {@code size} is zero, the return value depends on the particular library
     *             implementation (it may or may not be a null pointer), but the returned pointer shall not be
     *             dereferenced.
     *
     * @return on success, a pointer to the memory block allocated by the function
     *
     * @throws OutOfMemoryError if the function failed to allocate the requested block of memory
     */
    public static ByteBuffer memCalloc(int num, int size) {
        return wrapBufferByte(nmemCallocChecked(num, size), num * size);
    }

    /**
     * Alternative version of {@link #memCalloc}.
     *
     * @param num the number of bytes to allocate.
     */
    public static ByteBuffer memCalloc(int num) {
        return wrapBufferByte(nmemCallocChecked(num, 1), num);
    }

    /**
     * ShortBuffer version of {@link #memCalloc}.
     *
     * @param num the number of short values to allocate.
     */
    public static ShortBuffer memCallocShort(int num) {
        return wrapBufferShort(nmemCallocChecked(num, 2), num);
    }

    /**
     * IntBuffer version of {@link #memCalloc}.
     *
     * @param num the number of int values to allocate.
     */
    public static IntBuffer memCallocInt(int num) {
        return wrapBufferInt(nmemCallocChecked(num, 4), num);
    }

    /**
     * FloatBuffer version of {@link #memCalloc}.
     *
     * @param num the number of float values to allocate.
     */
    public static FloatBuffer memCallocFloat(int num) {
        return wrapBufferFloat(nmemCallocChecked(num, 4), num);
    }

    /**
     * LongBuffer version of {@link #memCalloc}.
     *
     * @param num the number of long values to allocate.
     */
    public static LongBuffer memCallocLong(int num) {
        return wrapBufferLong(nmemCallocChecked(num, 8), num);
    }

    /**
     * DoubleBuffer version of {@link #memCalloc}.
     *
     * @param num the number of double values to allocate.
     */
    public static DoubleBuffer memCallocDouble(int num) {
        return wrapBufferDouble(nmemCallocChecked(num, 8), num);
    }

    /**
     * PointerBuffer version of {@link #memCalloc}.
     *
     * @param num the number of pointer values to allocate.
     */
    public static PointerBuffer memCallocPointer(int num) {
        return PointerBuffer.create(memCalloc(Math.toIntExact(getAllocationSize(num, POINTER_SHIFT))));
    }

    /**
     * Unsafe version of {@link #memRealloc}. May return {@link #NULL} if {@code size} is zero or the allocation failed.
     */
    public static long nmemRealloc(long ptr, long size) {
        return ALLOCATOR.realloc(ptr, size);
    }

    /**
     * Unsafe version of {@link #memRealloc} that checks the returned pointer.
     *
     * @return a pointer to the memory block reallocated by the function on success. This pointer will never be
     *         {@link #NULL}, even if {@code size} is zero.
     *
     * @throws OutOfMemoryError if the function failed to allocate the requested block of memory
     */
    public static long nmemReallocChecked(long ptr, long size) {
        long address = nmemRealloc(ptr, size != 0 ? size : 1L);
        if (CHECKS && address == NULL) {
            throw new OutOfMemoryError();
        }
        return address;
    }

    private static <T extends Buffer> T realloc(@Nullable T old_p, T new_p, int size) {
        if (old_p != null) {
            new_p.position(min(old_p.position(), size));
        }
        return new_p;
    }

    /**
     * The standard C realloc function.
     *
     * <p>
     * Changes the size of the memory block pointed to by {@code ptr}. The function may move the memory block to a new
     * location (whose address is returned by the function). The content of the memory block is preserved up to the
     * lesser of the new and old sizes, even if the block is moved to a new location. If the new size is larger, the
     * value of the newly allocated portion is indeterminate.
     * </p>
     *
     * <p>
     * The memory address used is always the address at the start of {@code ptr}, so the current position of {@code ptr}
     * does not need to be set to 0 for this function to work. The current position is preserved, even if the memory
     * block is moved to a new location, unless {@code size} is less than the current position in which case position
     * will be equal to capacity. The limit is set to the capacity, and the mark is discarded.
     * </p>
     *
     * @param ptr  a pointer to a memory block previously allocated with {@link #memAlloc}, {@link #memCalloc} or
     *             {@link #memRealloc}. Alternatively, this can be a {@link #NULL} pointer, in which case a new block is
     *             allocated (as if {@link #memAlloc} was called).
     * @param size the new size for the memory block, in bytes.
     *
     * @return a pointer to the reallocated memory block, which may be either the same as {@code ptr} or a new location
     *
     * @throws OutOfMemoryError if the function failed to allocate the requested block of memory. The memory block
     *                          pointed to by argument {@code ptr} is not deallocated (it is still valid, and with its
     *                          contents unchanged).
     */
    public static ByteBuffer memRealloc(@Nullable ByteBuffer ptr, int size) {
        return realloc(
                ptr,
                memByteBuffer(nmemReallocChecked(ptr == null ? NULL : MemoryUtil.memAddress0(ptr), size), size),
                size);
    }

    /**
     * ShortBuffer version of {@link #memRealloc}.
     *
     * @param size the number of short values to allocate.
     */
    public static ShortBuffer memRealloc(@Nullable ShortBuffer ptr, int size) {
        return realloc(
                ptr,
                memShortBuffer(
                        nmemReallocChecked(
                                ptr == null ? NULL : MemoryUtil.memAddress0(ptr),
                                getAllocationSize(size, 1)),
                        size),
                size);
    }

    /**
     * IntBuffer version of {@link #memRealloc}.
     *
     * @param size the number of int values to allocate.
     */
    public static IntBuffer memRealloc(@Nullable IntBuffer ptr, int size) {
        return realloc(
                ptr,
                memIntBuffer(
                        nmemReallocChecked(
                                ptr == null ? NULL : MemoryUtil.memAddress0(ptr),
                                getAllocationSize(size, 2)),
                        size),
                size);
    }

    /**
     * LongBuffer version of {@link #memRealloc}.
     *
     * @param size the number of long values to allocate.
     */
    public static LongBuffer memRealloc(@Nullable LongBuffer ptr, int size) {
        return realloc(
                ptr,
                memLongBuffer(
                        nmemReallocChecked(
                                ptr == null ? NULL : MemoryUtil.memAddress0(ptr),
                                getAllocationSize(size, 3)),
                        size),
                size);
    }

    /**
     * FloatBuffer version of {@link #memRealloc}.
     *
     * @param size the number of float values to allocate.
     */
    public static FloatBuffer memRealloc(@Nullable FloatBuffer ptr, int size) {
        return realloc(
                ptr,
                memFloatBuffer(
                        nmemReallocChecked(
                                ptr == null ? NULL : MemoryUtil.memAddress0(ptr),
                                getAllocationSize(size, 2)),
                        size),
                size);
    }

    /**
     * DoubleBuffer version of {@link #memRealloc}.
     *
     * @param size the number of double values to allocate.
     */
    public static DoubleBuffer memRealloc(@Nullable DoubleBuffer ptr, int size) {
        return realloc(
                ptr,
                memDoubleBuffer(
                        nmemReallocChecked(
                                ptr == null ? NULL : MemoryUtil.memAddress0(ptr),
                                getAllocationSize(size, 3)),
                        size),
                size);
    }

    /**
     * PointerBuffer version of {@link #memRealloc}.
     *
     * @param size the number of pointer values to allocate.
     */
    public static PointerBuffer memRealloc(@Nullable PointerBuffer ptr, int size) {

        PointerBuffer buffer = memPointerBuffer(
                nmemReallocChecked(ptr == null ? NULL : memAddress(ptr), getAllocationSize(size, POINTER_SHIFT)),
                size);
        if (ptr != null) {
            buffer.position(min(ptr.position(), size));
        }
        return buffer;
    }

    /**
     * Returns the memory address of the specified buffer. [INTERNAL USE ONLY]
     */
    public static long memAddress0(Buffer buffer) {
        return MemoryUtil.memAddress0(buffer);
    }

    /** {@code ByteBuffer} version of {@link #memAddress0(Buffer)}. */
    public static long memAddress0(ByteBuffer buffer) {
        return MemoryUtil.memAddress0(buffer);
    }

    /** {@code ShortBuffer} version of {@link #memAddress0(Buffer)}. */
    public static long memAddress0(ShortBuffer buffer) {
        return MemoryUtil.memAddress0(buffer);
    }

    /** {@code CharBuffer} version of {@link #memAddress0(Buffer)}. */
    public static long memAddress0(CharBuffer buffer) {
        return MemoryUtil.memAddress0(buffer);
    }

    /** {@code IntBuffer} version of {@link #memAddress0(Buffer)}. */
    public static long memAddress0(IntBuffer buffer) {
        return MemoryUtil.memAddress0(buffer);
    }

    /** {@code LongBuffer} version of {@link #memAddress0(Buffer)}. */
    public static long memAddress0(LongBuffer buffer) {
        return MemoryUtil.memAddress0(buffer);
    }

    /** {@code FloatBuffer} version of {@link #memAddress0(Buffer)}. */
    public static long memAddress0(FloatBuffer buffer) {
        return MemoryUtil.memAddress0(buffer);
    }

    /** {@code DoubleBuffer} version of {@link #memAddress0(Buffer)}. */
    public static long memAddress0(DoubleBuffer buffer) {
        return MemoryUtil.memAddress0(buffer);
    }

    /**
     * Returns the memory address at the current position of the specified buffer. This is effectively a pointer value
     * that can be used in native function calls.
     */
    public static long memAddress(ByteBuffer buffer) {
        return buffer.position() + memAddress0(buffer);
    }

    /**
     * Returns the memory address at the specified position of the specified buffer.
     *
     * @see #memAddress(ByteBuffer)
     */
    public static long memAddress(ByteBuffer buffer, int position) {
        Objects.requireNonNull(buffer);
        return memAddress0(buffer) + Integer.toUnsignedLong(position);
    }

    private static long address(int position, int elementShift, long address) {
        return address + ((position & 0xFFFF_FFFFL) << elementShift);
    }

    /** ShortBuffer version of {@link #memAddress(ByteBuffer)}. */
    public static long memAddress(ShortBuffer buffer) {
        return address(buffer.position(), 1, memAddress0(buffer));
    }

    /** ShortBuffer version of {@link #memAddress(ByteBuffer, int)}. */
    public static long memAddress(ShortBuffer buffer, int position) {
        Objects.requireNonNull(buffer);
        return address(position, 1, memAddress0(buffer));
    }

    /** CharBuffer version of {@link #memAddress(ByteBuffer)}. */
    public static long memAddress(CharBuffer buffer) {
        return address(buffer.position(), 1, memAddress0(buffer));
    }

    /** CharBuffer version of {@link #memAddress(ByteBuffer, int)}. */
    public static long memAddress(CharBuffer buffer, int position) {
        Objects.requireNonNull(buffer);
        return address(position, 1, memAddress0(buffer));
    }

    /** IntBuffer version of {@link #memAddress(ByteBuffer)}. */
    public static long memAddress(IntBuffer buffer) {
        return address(buffer.position(), 2, memAddress0(buffer));
    }

    /** IntBuffer version of {@link #memAddress(ByteBuffer, int)}. */
    public static long memAddress(IntBuffer buffer, int position) {
        Objects.requireNonNull(buffer);
        return address(position, 2, memAddress0(buffer));
    }

    /** FloatBuffer version of {@link #memAddress(ByteBuffer)}. */
    public static long memAddress(FloatBuffer buffer) {
        return address(buffer.position(), 2, memAddress0(buffer));
    }

    /** FloatBuffer version of {@link #memAddress(ByteBuffer, int)}. */
    public static long memAddress(FloatBuffer buffer, int position) {
        Objects.requireNonNull(buffer);
        return address(position, 2, memAddress0(buffer));
    }

    /** LongBuffer version of {@link #memAddress(ByteBuffer)}. */
    public static long memAddress(LongBuffer buffer) {
        return address(buffer.position(), 3, memAddress0(buffer));
    }

    /** LongBuffer version of {@link #memAddress(ByteBuffer, int)}. */
    public static long memAddress(LongBuffer buffer, int position) {
        Objects.requireNonNull(buffer);
        return address(position, 3, memAddress0(buffer));
    }

    /** DoubleBuffer version of {@link #memAddress(ByteBuffer)}. */
    public static long memAddress(DoubleBuffer buffer) {
        return address(buffer.position(), 3, memAddress0(buffer));
    }

    /** DoubleBuffer version of {@link #memAddress(ByteBuffer, int)}. */
    public static long memAddress(DoubleBuffer buffer, int position) {
        Objects.requireNonNull(buffer);
        return address(position, 3, memAddress0(buffer));
    }

    /** PointerBuffer version of {@link #memAddress(ByteBuffer)}. */
    public static long memAddress(PointerBuffer buffer) {
        return address(buffer.position(), POINTER_SHIFT, memAddress(buffer));
    }

    /** PointerBuffer version of {@link #memAddress(ByteBuffer, int)}. */
    public static long memAddress(PointerBuffer buffer, int position) {
        Objects.requireNonNull(buffer);
        return address(position, POINTER_SHIFT, memAddress(buffer));
    }

    /** Polymorphic version of {@link #memAddress(ByteBuffer)}. */
    public static long memAddress(Buffer buffer) {
        int elementShift;
        if (buffer instanceof ByteBuffer) {
            elementShift = 0;
        } else if (buffer instanceof ShortBuffer || buffer instanceof CharBuffer) {
            elementShift = 1;
        } else if (buffer instanceof IntBuffer || buffer instanceof FloatBuffer) {
            elementShift = 2;
        } else {
            elementShift = 3;
        }
        return address(buffer.position(), elementShift, MemoryUtil.memAddress0(buffer));
    }

    /** Null-safe version of {@link #memAddress(ByteBuffer)}. Returns {@link #NULL} if the specified buffer is null. */
    public static long memAddressSafe(@Nullable ByteBuffer buffer) {
        return buffer == null ? NULL : memAddress0(buffer) + buffer.position();
    }

    /** ShortBuffer version of {@link #memAddressSafe(ByteBuffer)}. */
    public static long memAddressSafe(@Nullable ShortBuffer buffer) {
        return buffer == null ? NULL : address(buffer.position(), 1, memAddress0(buffer));
    }

    /** CharBuffer version of {@link #memAddressSafe(ByteBuffer)}. */
    public static long memAddressSafe(@Nullable CharBuffer buffer) {
        return buffer == null ? NULL : address(buffer.position(), 1, memAddress0(buffer));
    }

    /** IntBuffer version of {@link #memAddressSafe(ByteBuffer)}. */
    public static long memAddressSafe(@Nullable IntBuffer buffer) {
        return buffer == null ? NULL : address(buffer.position(), 2, memAddress0(buffer));
    }

    /** FloatBuffer version of {@link #memAddressSafe(ByteBuffer)}. */
    public static long memAddressSafe(@Nullable FloatBuffer buffer) {
        return buffer == null ? NULL : address(buffer.position(), 2, memAddress0(buffer));
    }

    /** LongBuffer version of {@link #memAddressSafe(ByteBuffer)}. */
    public static long memAddressSafe(@Nullable LongBuffer buffer) {
        return buffer == null ? NULL : address(buffer.position(), 3, memAddress0(buffer));
    }

    /** DoubleBuffer version of {@link #memAddressSafe(ByteBuffer)}. */
    public static long memAddressSafe(@Nullable DoubleBuffer buffer) {
        return buffer == null ? NULL : address(buffer.position(), 3, memAddress0(buffer));
    }

    /** Pointer version of {@link #memAddressSafe(ByteBuffer)}. */
    public static long memAddressSafe(@Nullable Pointer pointer) {
        return pointer == null ? NULL : pointer.address();
    }

    /**
     * Creates a new direct ByteBuffer that starts at the specified memory address and has the specified capacity. The
     * returned ByteBuffer instance will be set to the native {@link ByteOrder}.
     */
    public static ByteBuffer memByteBuffer(long address, int capacity) {
        if (CHECKS) {
            check(address);
        }
        return wrapBufferByte(address, capacity);
    }

    /**
     * Like {@link #memByteBuffer(long, int) memByteBuffer}, but returns {@code null} if {@code address} is
     * {@link #NULL}.
     */
    public static @Nullable ByteBuffer memByteBufferSafe(long address, int capacity) {
        return address == NULL ? null : wrapBufferByte(address, capacity);
    }

    /**
     * Creates a {@link ByteBuffer} instance as a view of the specified {@link ShortBuffer} between its current position
     * and limit.
     *
     * <p>
     * This operation is the inverse of {@link ByteBuffer#asShortBuffer()}. The returned {@code ByteBuffer} instance
     * will be set to the native {@link ByteOrder}.
     * </p>
     */
    public static ByteBuffer memByteBuffer(ShortBuffer buffer) {
        if (CHECKS && (Integer.MAX_VALUE >> 1) < buffer.remaining()) {
            throw new IllegalStateException("The source buffer range is too wide");
        }
        return wrapBufferByte(memAddress(buffer), buffer.remaining() << 1);
    }

    /**
     * Creates a {@link ByteBuffer} instance as a view of the specified {@link CharBuffer} between its current position
     * and limit.
     *
     * <p>
     * This operation is the inverse of {@link ByteBuffer#asCharBuffer()}. The returned {@code ByteBuffer} instance will
     * be set to the native {@link ByteOrder}.
     * </p>
     */
    public static ByteBuffer memByteBuffer(CharBuffer buffer) {
        if (CHECKS && (Integer.MAX_VALUE >> 1) < buffer.remaining()) {
            throw new IllegalStateException("The source buffer range is too wide");
        }
        return wrapBufferByte(memAddress(buffer), buffer.remaining() << 1);
    }

    /**
     * Creates a {@link ByteBuffer} instance as a view of the specified {@link IntBuffer} between its current position
     * and limit.
     *
     * <p>
     * This operation is the inverse of {@link ByteBuffer#asIntBuffer()}. The returned {@code ByteBuffer} instance will
     * be set to the native {@link ByteOrder}.
     * </p>
     */
    public static ByteBuffer memByteBuffer(IntBuffer buffer) {
        if (CHECKS && (Integer.MAX_VALUE >> 2) < buffer.remaining()) {
            throw new IllegalStateException("The source buffer range is too wide");
        }
        return wrapBufferByte(memAddress(buffer), buffer.remaining() << 2);
    }

    /**
     * Creates a {@link ByteBuffer} instance as a view of the specified {@link LongBuffer} between its current position
     * and limit.
     *
     * <p>
     * This operation is the inverse of {@link ByteBuffer#asLongBuffer()}. The returned {@code ByteBuffer} instance will
     * be set to the native {@link ByteOrder}.
     * </p>
     */
    public static ByteBuffer memByteBuffer(LongBuffer buffer) {
        if (CHECKS && (Integer.MAX_VALUE >> 3) < buffer.remaining()) {
            throw new IllegalStateException("The source buffer range is too wide");
        }
        return wrapBufferByte(memAddress(buffer), buffer.remaining() << 3);
    }

    /**
     * Creates a {@link ByteBuffer} instance as a view of the specified {@link FloatBuffer} between its current position
     * and limit.
     *
     * <p>
     * This operation is the inverse of {@link ByteBuffer#asFloatBuffer()}. The returned {@code ByteBuffer} instance
     * will be set to the native {@link ByteOrder}.
     * </p>
     */
    public static ByteBuffer memByteBuffer(FloatBuffer buffer) {
        if (CHECKS && (Integer.MAX_VALUE >> 2) < buffer.remaining()) {
            throw new IllegalStateException("The source buffer range is too wide");
        }
        return wrapBufferByte(memAddress(buffer), buffer.remaining() << 2);
    }

    /**
     * Creates a {@link ByteBuffer} instance as a view of the specified {@link DoubleBuffer} between its current
     * position and limit.
     *
     * <p>
     * This operation is the inverse of {@link ByteBuffer#asDoubleBuffer()}. The returned {@code ByteBuffer} instance
     * will be set to the native {@link ByteOrder}.
     * </p>
     */
    public static ByteBuffer memByteBuffer(DoubleBuffer buffer) {
        if (CHECKS && (Integer.MAX_VALUE >> 3) < buffer.remaining()) {
            throw new IllegalStateException("The source buffer range is too wide");
        }
        return wrapBufferByte(memAddress(buffer), buffer.remaining() << 3);
    }

    /**
     * Creates a new direct ShortBuffer that starts at the specified memory address and has the specified capacity.
     *
     * <p>
     * The {@code address} specified must be aligned to 2 bytes. If not, use
     * {@code memByteBuffer(address, capacity * 2).asShortBuffer()}.
     * </p>
     */
    public static ShortBuffer memShortBuffer(long address, int capacity) {
        if (CHECKS) {
            check(address);
        }
        return wrapBufferShort(address, capacity);
    }

    /** Like {@link #memShortBuffer}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable ShortBuffer memShortBufferSafe(long address, int capacity) {
        return address == NULL ? null : wrapBufferShort(address, capacity);
    }

    /**
     * Creates a new direct CharBuffer that starts at the specified memory address and has the specified capacity.
     *
     * <p>
     * The {@code address} specified must be aligned to 2 bytes. If not, use
     * {@code memByteBuffer(address, capacity * 2).asCharBuffer()}.
     * </p>
     */
    public static CharBuffer memCharBuffer(long address, int capacity) {
        if (CHECKS) {
            check(address);
        }
        return wrapBufferChar(address, capacity);
    }

    /** Like {@link #memCharBuffer}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable CharBuffer memCharBufferSafe(long address, int capacity) {
        return address == NULL ? null : wrapBufferChar(address, capacity);
    }

    /**
     * Creates a new direct IntBuffer that starts at the specified memory address and has the specified capacity.
     *
     * <p>
     * The {@code address} specified must be aligned to 4 bytes. If not, use
     * {@code memByteBuffer(address, capacity * 4).asIntBuffer()}.
     * </p>
     */
    public static IntBuffer memIntBuffer(long address, int capacity) {
        if (CHECKS) {
            check(address);
        }
        return wrapBufferInt(address, capacity);
    }

    /** Like {@link #memIntBuffer}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable IntBuffer memIntBufferSafe(long address, int capacity) {
        return address == NULL ? null : wrapBufferInt(address, capacity);
    }

    /**
     * Creates a new direct LongBuffer that starts at the specified memory address and has the specified capacity.
     *
     * <p>
     * The {@code address} specified must be aligned to 8 bytes. If not, use
     * {@code memByteBuffer(address, capacity * 8).asLongBuffer()}.
     * </p>
     */
    public static LongBuffer memLongBuffer(long address, int capacity) {
        if (CHECKS) {
            check(address);
        }
        return wrapBufferLong(address, capacity);
    }

    /** Like {@link #memLongBuffer}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable LongBuffer memLongBufferSafe(long address, int capacity) {
        return address == NULL ? null : wrapBufferLong(address, capacity);
    }

    /**
     * Creates a new direct FloatBuffer that starts at the specified memory address and has the specified capacity.
     *
     * <p>
     * The {@code address} specified must be aligned to 4 bytes. If not, use
     * {@code memByteBuffer(address, capacity * 4).asFloatBuffer()}.
     * </p>
     */
    public static FloatBuffer memFloatBuffer(long address, int capacity) {
        if (CHECKS) {
            check(address);
        }
        return wrapBufferFloat(address, capacity);
    }

    /** Like {@link #memFloatBuffer}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable FloatBuffer memFloatBufferSafe(long address, int capacity) {
        return address == NULL ? null : wrapBufferFloat(address, capacity);
    }

    /**
     * Creates a new direct DoubleBuffer that starts at the specified memory address and has the specified capacity.
     *
     * <p>
     * The {@code address} specified must be aligned to 8 bytes. If not, use
     * {@code memByteBuffer(address, capacity * 8).asDoubleBuffer()}.
     * </p>
     */
    public static DoubleBuffer memDoubleBuffer(long address, int capacity) {
        if (CHECKS) {
            check(address);
        }
        return wrapBufferDouble(address, capacity);
    }

    /** Like {@link #memDoubleBuffer}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable DoubleBuffer memDoubleBufferSafe(long address, int capacity) {
        return address == NULL ? null : wrapBufferDouble(address, capacity);
    }

    /**
     * Creates a new PointerBuffer that starts at the specified memory address and has the specified capacity.
     *
     * <p>
     * The {@code address} specified must be aligned to the pointer size. If not, use
     * {@code PointerBuffer.create(memByteBuffer(address, capacity *
     * POINTER_SIZE))}.
     * </p>
     */
    public static PointerBuffer memPointerBuffer(long address, int capacity) {
        if (CHECKS) {
            check(address);
        }
        return PointerBuffer.create(memByteBuffer(address, capacity));
    }

    /** Like {@link #memPointerBuffer}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable PointerBuffer memPointerBufferSafe(long address, int capacity) {
        return address == NULL ? null : PointerBuffer.create(memByteBuffer(address, capacity));
    }

    /**
     * Duplicates the specified buffer. The returned buffer will have the same {@link ByteOrder} as the source buffer.
     *
     * <p>
     * This method should be preferred over {@link ByteBuffer#duplicate} because it has a much shorter call chain. Long
     * call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static ByteBuffer memDuplicate(ByteBuffer buffer) {
        return MemoryUtil.memDuplicate(buffer);
    }

    /**
     * Duplicates the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link ShortBuffer#duplicate} because it has a much shorter call chain. Long
     * call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static ShortBuffer memDuplicate(ShortBuffer buffer) {
        return MemoryUtil.memDuplicate(buffer);
    }

    /**
     * Duplicates the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link CharBuffer#duplicate} because it has a much shorter call chain. Long
     * call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static CharBuffer memDuplicate(CharBuffer buffer) {
        return MemoryUtil.memDuplicate(buffer);
    }

    /**
     * Duplicates the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link IntBuffer#duplicate} because it has a much shorter call chain. Long
     * call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static IntBuffer memDuplicate(IntBuffer buffer) {
        return MemoryUtil.memDuplicate(buffer);
    }

    /**
     * Duplicates the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link LongBuffer#duplicate} because it has a much shorter call chain. Long
     * call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static LongBuffer memDuplicate(LongBuffer buffer) {
        return MemoryUtil.memDuplicate(buffer);
    }

    /**
     * Duplicates the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link FloatBuffer#duplicate} because it has a much shorter call chain. Long
     * call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static FloatBuffer memDuplicate(FloatBuffer buffer) {
        return MemoryUtil.memDuplicate(buffer);
    }

    /**
     * Duplicates the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link DoubleBuffer#duplicate} because it has a much shorter call chain.
     * Long call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement
     * via Escape Analysis).
     * </p>
     */
    public static DoubleBuffer memDuplicate(DoubleBuffer buffer) {
        return MemoryUtil.memDuplicate(buffer);
    }

    /**
     * Slices the specified buffer. The returned buffer will have the same {@link ByteOrder} as the source buffer.
     *
     * <p>
     * This method should be preferred over {@link ByteBuffer#slice} because it has a much shorter call chain. Long call
     * chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static ByteBuffer memSlice(ByteBuffer buffer) {
        return MemoryUtil.memSlice(buffer);
    }

    /**
     * Slices the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link ShortBuffer#slice} because it has a much shorter call chain. Long
     * call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static ShortBuffer memSlice(ShortBuffer buffer) {
        return MemoryUtil.memSlice(buffer);
    }

    /**
     * Slices the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link CharBuffer#slice} because it has a much shorter call chain. Long call
     * chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static CharBuffer memSlice(CharBuffer buffer) {
        return MemoryUtil.memSlice(buffer);
    }

    /**
     * Slices the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link IntBuffer#slice} because it has a much shorter call chain. Long call
     * chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static IntBuffer memSlice(IntBuffer buffer) {
        return MemoryUtil.memSlice(buffer);
    }

    /**
     * Slices the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link LongBuffer#slice} because it has a much shorter call chain. Long call
     * chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static LongBuffer memSlice(LongBuffer buffer) {
        return MemoryUtil.memSlice(buffer);
    }

    /**
     * Slices the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link FloatBuffer#slice} because it has a much shorter call chain. Long
     * call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static FloatBuffer memSlice(FloatBuffer buffer) {
        return MemoryUtil.memSlice(buffer);
    }

    /**
     * Slices the specified buffer.
     *
     * <p>
     * This method should be preferred over {@link DoubleBuffer#slice} because it has a much shorter call chain. Long
     * call chains may fail to inline due to JVM limits, disabling important optimizations (e.g. scalar replacement via
     * Escape Analysis).
     * </p>
     */
    public static DoubleBuffer memSlice(DoubleBuffer buffer) {
        return MemoryUtil.memSlice(buffer);
    }

    /**
     * Returns a slice of the specified buffer between {@code (buffer.position() + offset)} and
     * {@code (buffer.position() + offset + capacity)}. The returned buffer will have the same {@link ByteOrder} as the
     * original buffer.
     *
     * <p>
     * The position and limit of the original buffer are preserved after a call to this method.
     * </p>
     *
     * @param offset   the slice offset, it must be &le; {@code buffer.remaining()}
     * @param capacity the slice length, it must be &le; {@code buffer.capacity() - (buffer.position() + offset)}
     */
    public static ByteBuffer memSlice(ByteBuffer buffer, int offset, int capacity) {
        checkSliceRange(buffer, offset, capacity);
        return MemoryUtil.memSlice(buffer, offset, capacity);
    }

    /**
     * Returns a slice of the specified buffer between {@code (buffer.position() + offset)} and
     * {@code (buffer.position() + offset + capacity)}.
     *
     * <p>
     * The position and limit of the original buffer are preserved after a call to this method.
     * </p>
     *
     * @param offset   the slice offset, it must be &le; {@code buffer.remaining()}
     * @param capacity the slice length, it must be &le; {@code buffer.capacity() - (buffer.position() + offset)}
     */
    public static ShortBuffer memSlice(ShortBuffer buffer, int offset, int capacity) {
        checkSliceRange(buffer, offset, capacity);
        return MemoryUtil.memSlice(buffer, offset, capacity);
    }

    /**
     * Returns a slice of the specified buffer between {@code (buffer.position() + offset)} and
     * {@code (buffer.position() + offset + capacity)}.
     *
     * <p>
     * The position and limit of the original buffer are preserved after a call to this method.
     * </p>
     *
     * @param offset   the slice offset, it must be &le; {@code buffer.remaining()}
     * @param capacity the slice length, it must be &le; {@code buffer.capacity() - (buffer.position() + offset)}
     */
    public static CharBuffer memSlice(CharBuffer buffer, int offset, int capacity) {
        checkSliceRange(buffer, offset, capacity);
        return MemoryUtil.memSlice(buffer, offset, capacity);
    }

    /**
     * Returns a slice of the specified buffer between {@code (buffer.position() + offset)} and
     * {@code (buffer.position() + offset + capacity)}.
     *
     * <p>
     * The position and limit of the original buffer are preserved after a call to this method.
     * </p>
     *
     * @param offset   the slice offset, it must be &le; {@code buffer.remaining()}
     * @param capacity the slice length, it must be &le; {@code buffer.capacity() - (buffer.position() + offset)}
     */
    public static IntBuffer memSlice(IntBuffer buffer, int offset, int capacity) {
        checkSliceRange(buffer, offset, capacity);
        return MemoryUtil.memSlice(buffer, offset, capacity);
    }

    /**
     * Returns a slice of the specified buffer between {@code (buffer.position() + offset)} and
     * {@code (buffer.position() + offset + capacity)}.
     *
     * <p>
     * The position and limit of the original buffer are preserved after a call to this method.
     * </p>
     *
     * @param offset   the slice offset, it must be &le; {@code buffer.remaining()}
     * @param capacity the slice length, it must be &le; {@code buffer.capacity() - (buffer.position() + offset)}
     */
    public static LongBuffer memSlice(LongBuffer buffer, int offset, int capacity) {
        checkSliceRange(buffer, offset, capacity);
        return MemoryUtil.memSlice(buffer, offset, capacity);
    }

    /**
     * Returns a slice of the specified buffer between {@code (buffer.position() + offset)} and
     * {@code (buffer.position() + offset + capacity)}.
     *
     * <p>
     * The position and limit of the original buffer are preserved after a call to this method.
     * </p>
     *
     * @param offset   the slice offset, it must be &le; {@code buffer.remaining()}
     * @param capacity the slice length, it must be &le; {@code buffer.capacity() - (buffer.position() + offset)}
     */
    public static FloatBuffer memSlice(FloatBuffer buffer, int offset, int capacity) {
        checkSliceRange(buffer, offset, capacity);
        return MemoryUtil.memSlice(buffer, offset, capacity);
    }

    /**
     * Returns a slice of the specified buffer between {@code (buffer.position() + offset)} and
     * {@code (buffer.position() + offset + capacity)}.
     *
     * <p>
     * The position and limit of the original buffer are preserved after a call to this method.
     * </p>
     *
     * @param offset   the slice offset, it must be &le; {@code buffer.remaining()}
     * @param capacity the slice length, it must be &le; {@code buffer.capacity() - (buffer.position() + offset)}
     */
    public static DoubleBuffer memSlice(DoubleBuffer buffer, int offset, int capacity) {
        checkSliceRange(buffer, offset, capacity);
        return MemoryUtil.memSlice(buffer, offset, capacity);
    }

    private static void checkSliceRange(Buffer buffer, int offset, int capacity) {
        int position = buffer.position() + offset;
        if (offset < 0 || buffer.limit() < position) {
            throw new IllegalArgumentException();
        }
        if (capacity < 0 || buffer.capacity() - position < capacity) {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Sets all bytes in a specified block of memory to a fixed value (usually zero).
     *
     * @param value the value to set (memSet will convert it to unsigned byte)
     */
    public static void memSet(ByteBuffer ptr, int value) {
        memSet(memAddress(ptr), value, ptr.remaining());
    }

    /**
     * Sets all bytes in a specified block of memory to a fixed value (usually zero).
     *
     * @param value the value to set (memSet will convert it to unsigned byte)
     */
    public static void memSet(ShortBuffer ptr, int value) {
        memSet(memAddress(ptr), value, apiGetBytes(ptr.remaining(), 1));
    }

    /**
     * Sets all bytes in a specified block of memory to a fixed value (usually zero).
     *
     * @param value the value to set (memSet will convert it to unsigned byte)
     */
    public static void memSet(CharBuffer ptr, int value) {
        memSet(memAddress(ptr), value, apiGetBytes(ptr.remaining(), 1));
    }

    /**
     * Sets all bytes in a specified block of memory to a fixed value (usually zero).
     *
     * @param value the value to set (memSet will convert it to unsigned byte)
     */
    public static void memSet(IntBuffer ptr, int value) {
        memSet(memAddress(ptr), value, apiGetBytes(ptr.remaining(), 2));
    }

    /**
     * Sets all bytes in a specified block of memory to a fixed value (usually zero).
     *
     * @param value the value to set (memSet will convert it to unsigned byte)
     */
    public static void memSet(LongBuffer ptr, int value) {
        memSet(memAddress(ptr), value, apiGetBytes(ptr.remaining(), 3));
    }

    /**
     * Sets all bytes in a specified block of memory to a fixed value (usually zero).
     *
     * @param value the value to set (memSet will convert it to unsigned byte)
     */
    public static void memSet(FloatBuffer ptr, int value) {
        memSet(memAddress(ptr), value, apiGetBytes(ptr.remaining(), 2));
    }

    /**
     * Sets all bytes in a specified block of memory to a fixed value (usually zero).
     *
     * @param value the value to set (memSet will convert it to unsigned byte)
     */
    public static void memSet(DoubleBuffer ptr, int value) {
        memSet(memAddress(ptr), value, apiGetBytes(ptr.remaining(), 3));
    }

    /**
     * Sets all bytes in a specified block of memory to a copy of another block.
     */
    public static void memCopy(ByteBuffer src, ByteBuffer dst) {
        if (CHECKS) {
            check(dst, src.remaining());
        }
        MultiReleaseMemCopy.copy(memAddress(src), memAddress(dst), src.remaining());
    }

    /**
     * Sets all bytes in a specified block of memory to a copy of another block.
     */
    public static void memCopy(ShortBuffer src, ShortBuffer dst) {
        if (CHECKS) {
            check(dst, src.remaining());
        }
        MultiReleaseMemCopy.copy(memAddress(src), memAddress(dst), apiGetBytes(src.remaining(), 1));
    }

    /**
     * Sets all bytes in a specified block of memory to a copy of another block.
     */
    public static void memCopy(CharBuffer src, CharBuffer dst) {
        if (CHECKS) {
            check((Buffer) dst, src.remaining());
        }
        MultiReleaseMemCopy.copy(memAddress(src), memAddress(dst), apiGetBytes(src.remaining(), 1));
    }

    /**
     * Sets all bytes in a specified block of memory to a copy of another block.
     */
    public static void memCopy(IntBuffer src, IntBuffer dst) {
        if (CHECKS) {
            check(dst, src.remaining());
        }
        MultiReleaseMemCopy.copy(memAddress(src), memAddress(dst), apiGetBytes(src.remaining(), 2));
    }

    /**
     * Sets all bytes in a specified block of memory to a copy of another block.
     */
    public static void memCopy(LongBuffer src, LongBuffer dst) {
        if (CHECKS) {
            check(dst, src.remaining());
        }
        MultiReleaseMemCopy.copy(memAddress(src), memAddress(dst), apiGetBytes(src.remaining(), 3));
    }

    /**
     * Sets all bytes in a specified block of memory to a copy of another block.
     */
    public static void memCopy(FloatBuffer src, FloatBuffer dst) {
        if (CHECKS) {
            check(dst, src.remaining());
        }
        MultiReleaseMemCopy.copy(memAddress(src), memAddress(dst), apiGetBytes(src.remaining(), 2));
    }

    /**
     * Sets all bytes in a specified block of memory to a copy of another block.
     */
    public static void memCopy(DoubleBuffer src, DoubleBuffer dst) {
        if (CHECKS) {
            check(dst, src.remaining());
        }
        MultiReleaseMemCopy.copy(memAddress(src), memAddress(dst), apiGetBytes(src.remaining(), 3));
    }

    /**
     * Sets all bytes in a specified block of memory to a fixed value (usually zero).
     *
     * @param value the value to set (memSet will convert it to unsigned byte)
     * @param bytes the number of bytes to set
     */
    public static void memSet(long ptr, int value, long bytes) {
        if (DEBUG && (ptr == NULL || bytes < 0)) {
            throw new IllegalArgumentException();
        }

        MemoryUtil.memSet(ptr, value, bytes);
    }

    /**
     * Sets all bytes in a specified block of memory to a copy of another block.
     */
    public static void memCopy(long src, long dst, long bytes) {
        if (DEBUG && (src == NULL || dst == NULL || bytes < 0)) {
            throw new IllegalArgumentException();
        }

        MemoryUtil.memCopy(src, dst, bytes);
    }

    public static boolean memGetBoolean(long ptr) {
        return MemoryUtil.memGetBoolean(ptr);
    }

    public static byte memGetByte(long ptr) {
        return MemoryUtil.memGetByte(ptr);
    }

    public static short memGetShort(long ptr) {
        return MemoryUtil.memGetShort(ptr);
    }

    public static int memGetInt(long ptr) {
        return MemoryUtil.memGetInt(ptr);
    }

    public static long memGetLong(long ptr) {
        return MemoryUtil.memGetLong(ptr);
    }

    public static float memGetFloat(long ptr) {
        return MemoryUtil.memGetFloat(ptr);
    }

    public static double memGetDouble(long ptr) {
        return MemoryUtil.memGetDouble(ptr);
    }

    public static long memGetCLong(long ptr) {
        return MemoryUtil.memGetCLong(ptr);
    }

    public static long memGetAddress(long ptr) {
        return MemoryUtil.memGetAddress(ptr);
    }

    public static void memPutByte(long ptr, byte value) {
        MemoryUtil.memPutByte(ptr, value);
    }

    public static void memPutShort(long ptr, short value) {
        MemoryUtil.memPutShort(ptr, value);
    }

    public static void memPutInt(long ptr, int value) {
        MemoryUtil.memPutInt(ptr, value);
    }

    public static void memPutLong(long ptr, long value) {
        MemoryUtil.memPutLong(ptr, value);
    }

    public static void memPutFloat(long ptr, float value) {
        MemoryUtil.memPutFloat(ptr, value);
    }

    public static void memPutDouble(long ptr, double value) {
        MemoryUtil.memPutDouble(ptr, value);
    }

    public static void memPutCLong(long ptr, long value) {
        MemoryUtil.memPutCLong(ptr, value);
    }

    public static void memPutAddress(long ptr, long value) {
        MemoryUtil.memPutAddress(ptr, value);
    }

    /**
     * Returns the object that the specified global reference points to.
     *
     * @param <T>       the object type
     */
    public static native <T> T memGlobalRefToObject(long globalRef);

    private static int write8(long target, int offset, int value) {
        MemoryUtil.memPutByte(target + Integer.toUnsignedLong(offset), (byte) value);
        return offset + 1;
    }

    private static int write8Safe(long target, int offset, int maxLength, int value) {
        if (offset == maxLength) {
            throw new BufferOverflowException();
        }
        MemoryUtil.memPutByte(target + Integer.toUnsignedLong(offset), (byte) value);
        return offset + 1;
    }

    private static int write16(long target, int offset, char value) {
        MemoryUtil.memPutShort(target + Integer.toUnsignedLong(offset), (short) value);
        return offset + 2;
    }

    /**
     * Returns a ByteBuffer containing the specified text ASCII encoded and null-terminated.
     *
     * @return the encoded text. The returned buffer must be deallocated manually with {@link #memFree}.
     *
     * @throws BufferOverflowException if more than {@link Integer#MAX_VALUE} bytes are required to encode the text.
     */
    public static ByteBuffer memASCII(CharSequence text) {
        return memASCII(text, true);
    }

    /** Like {@link #memASCII(CharSequence) memASCII}, but returns {@code null} if {@code text} is {@code null}. */
    public static @Nullable ByteBuffer memASCIISafe(@Nullable CharSequence text) {
        return text == null ? null : memASCII(text, true);
    }

    /**
     * Returns a {@link ByteBuffer} containing the specified text ASCII encoded and optionally null-terminated.
     *
     * @param nullTerminated if true, the text will be terminated with a '\0'.
     *
     * @return the encoded text. The returned buffer must be deallocated manually with {@link #memFree}.
     *
     * @throws BufferOverflowException if more than {@link Integer#MAX_VALUE} bytes are required to encode the text.
     */
    public static ByteBuffer memASCII(CharSequence text, boolean nullTerminated) {
        int length = memLengthASCII(text, nullTerminated);
        long target = nmemAlloc(length);
        if (CHECKS && target == NULL) {
            throw new OutOfMemoryError();
        }
        encodeASCIIUnsafe(text, nullTerminated, target);
        return wrapBufferByte(target, length);
    }

    /**
     * Like {@link #memASCII(CharSequence, boolean) memASCII}, but returns {@code null} if {@code text} is {@code null}.
     */
    public static @Nullable ByteBuffer memASCIISafe(@Nullable CharSequence text, boolean nullTerminated) {
        return text == null ? null : memASCII(text, nullTerminated);
    }

    /**
     * Encodes and optionally null-terminates the specified text using ASCII encoding. The encoded text is stored in the
     * specified {@link ByteBuffer}, at the current buffer position. The current buffer position is not modified by this
     * operation.
     *
     * @param nullTerminated if true, the text will be terminated with a '\0'
     *
     * @return the number of bytes of the encoded string
     *
     * @throws BufferOverflowException if more than {@code target.remaining()} bytes are required to encode the text.
     */
    public static int memASCII(CharSequence text, boolean nullTerminated, ByteBuffer target) {
        if (target.remaining() < memLengthASCII(text, nullTerminated)) {
            throw new BufferOverflowException();
        }
        long address = memAddress(target);
        return encodeASCIIUnsafe(text, nullTerminated, address);
    }

    /**
     * Encodes and optionally null-terminates the specified text using ASCII encoding. The encoded text is stored in the
     * specified {@link ByteBuffer} at the specified {@code position} offset. The current buffer position is not
     * modified by this operation.
     *
     * @param nullTerminated if true, the text will be terminated with a '\0'.
     *
     * @return the number of bytes of the encoded string
     *
     * @throws BufferOverflowException if more than {@code target.capacity() - offset} bytes are required to encode the
     *                                 text.
     */
    public static int memASCII(CharSequence text, boolean nullTerminated, ByteBuffer target, int offset) {
        if (target.capacity() - offset < memLengthASCII(text, nullTerminated)) {
            throw new BufferOverflowException();
        }
        return encodeASCIIUnsafe(text, nullTerminated, memAddress(target, offset));
    }

    static int encodeASCIIUnsafe(CharSequence text, boolean nullTerminated, long target) {
        int i = 0, len = text.length();

        while (i < len) {
            i = write8(target, i, text.charAt(i));
        }

        if (nullTerminated) {
            i = write8(target, i, 0);
        }

        return i;
    }

    /**
     * Returns the number of bytes required to encode the specified text in the ASCII encoding.
     *
     * @param nullTerminated if true, add the number of bytes required for null-termination
     *
     * @throws BufferOverflowException if more than {@link Integer#MAX_VALUE} bytes are required to encode the text.
     */
    public static int memLengthASCII(CharSequence value, boolean nullTerminated) {
        int len = value.length() + (nullTerminated ? 1 : 0);
        if (len < 0) {
            throw new BufferOverflowException();
        }
        return len;
    }

    /**
     * Returns a ByteBuffer containing the specified text UTF-8 encoded and null-terminated.
     *
     * @return the encoded text. The returned buffer must be deallocated manually with {@link #memFree}.
     *
     * @throws BufferOverflowException if more than {@link Integer#MAX_VALUE} bytes are required to encode the text.
     */
    public static ByteBuffer memUTF8(CharSequence text) {
        return memUTF8(text, true);
    }

    /** Like {@link #memUTF8(CharSequence) memUTF8}, but returns {@code null} if {@code text} is {@code null}. */
    public static @Nullable ByteBuffer memUTF8Safe(@Nullable CharSequence text) {
        return text == null ? null : memUTF8(text, true);
    }

    /**
     * Returns a ByteBuffer containing the specified text UTF-8 encoded and optionally null-terminated.
     *
     * @param nullTerminated if true, the text will be terminated with a '\0'.
     *
     * @return the encoded text. The returned buffer must be deallocated manually with {@link #memFree}.
     *
     * @throws BufferOverflowException if more than {@link Integer#MAX_VALUE} bytes are required to encode the text.
     */
    public static ByteBuffer memUTF8(CharSequence text, boolean nullTerminated) {
        int length = memLengthUTF8(text, nullTerminated);
        long target = nmemAlloc(length);
        if (CHECKS && target == NULL) {
            throw new OutOfMemoryError();
        }
        encodeUTF8Unsafe(text, nullTerminated, target);
        return wrapBufferByte(target, length);
    }

    /**
     * Like {@link #memUTF8(CharSequence, boolean) memUTF8}, but returns {@code null} if {@code text} is {@code null}.
     */
    public static @Nullable ByteBuffer memUTF8Safe(@Nullable CharSequence text, boolean nullTerminated) {
        return text == null ? null : memUTF8(text, nullTerminated);
    }

    /**
     * Encodes and optionally null-terminates the specified text using UTF-8 encoding. The encoded text is stored in the
     * specified {@link ByteBuffer}, at the current buffer position. The current buffer position is not modified by this
     * operation.
     *
     * @param nullTerminated if true, the text will be terminated with a '\0'.
     *
     * @return the number of bytes of the encoded string
     *
     * @throws BufferOverflowException if more than {@code target.remaining} bytes are required to encode the text.
     */
    public static int memUTF8(CharSequence text, boolean nullTerminated, ByteBuffer target) {
        if (target.remaining() < memLengthASCII(text, nullTerminated)) {
            throw new BufferOverflowException();
        }
        return encodeUTF8Safe(text, nullTerminated, memAddress(target), target.remaining());
    }

    /**
     * Encodes and optionally null-terminates the specified text using UTF-8 encoding. The encoded text is stored in the
     * specified {@link ByteBuffer}, at the specified {@code position} offset. The current buffer position is not
     * modified by this operation.
     *
     * @param nullTerminated if true, the text will be terminated with a '\0'.
     *
     * @return the number of bytes of the encoded string
     *
     * @throws BufferOverflowException if more than {@code target.capacity() - offset} bytes are required to encode the
     *                                 text.
     */
    public static int memUTF8(CharSequence text, boolean nullTerminated, ByteBuffer target, int offset) {
        if (target.capacity() - offset < memLengthASCII(text, nullTerminated)) {
            throw new BufferOverflowException();
        }
        return encodeUTF8Safe(text, nullTerminated, memAddress(target, offset), target.capacity() - offset);
    }

    static int encodeUTF8Unsafe(CharSequence text, boolean nullTerminated, long target) {
        int p = 0, i = 0, len = text.length();

        while (i < len) {
            char c = text.charAt(i++);
            if (c < 0x80) {
                p = write8(target, p, c);
            } else {
                int cp = c;
                if (c < 0x800) {
                    p = write8(target, p, 0xC0 | cp >> 6);
                } else {
                    if (!isHighSurrogate(c)) {
                        p = write8(target, p, 0xE0 | cp >> 12);
                    } else {
                        cp = toCodePoint(c, text.charAt(i++));

                        p = write8(target, p, 0xF0 | cp >> 18);
                        p = write8(target, p, 0x80 | cp >> 12 & 0x3F);
                    }
                    p = write8(target, p, 0x80 | cp >> 6 & 0x3F);
                }
                p = write8(target, p, 0x80 | cp & 0x3F);
            }
        }

        if (nullTerminated) {
            p = write8(target, p, 0);
        }

        return p;
    }

    static int encodeUTF8Safe(CharSequence text, boolean nullTerminated, long target, int maxLength) {
        int p = 0, i = 0, length = text.length();

        // ASCII fast path
        while (i < length) {
            char c = text.charAt(i);
            if (0x80 <= c) {
                break;
            }
            p = write8(target, p, c); // have already checked that text.length() <= maxLength
            i++;
        }

        // Slow path
        while (i < length) {
            char c = text.charAt(i++);
            if (c < 0x80) {
                p = write8Safe(target, p, maxLength, c);
            } else {
                int cp = c;
                if (c < 0x800) {
                    p = write8Safe(target, p, maxLength, 0xC0 | cp >> 6);
                } else {
                    if (!isHighSurrogate(c)) {
                        p = write8Safe(target, p, maxLength, 0xE0 | cp >> 12);
                    } else {
                        cp = toCodePoint(c, text.charAt(i++));

                        p = write8Safe(target, p, maxLength, 0xF0 | cp >> 18);
                        p = write8Safe(target, p, maxLength, 0x80 | cp >> 12 & 0x3F);
                    }
                    p = write8Safe(target, p, maxLength, 0x80 | cp >> 6 & 0x3F);
                }
                p = write8Safe(target, p, maxLength, 0x80 | cp & 0x3F);
            }
        }

        if (nullTerminated) {
            p = write8Safe(target, p, maxLength, 0);
        }

        return p;
    }

    /**
     * Returns the number of bytes required to encode the specified text in the UTF-8 encoding.
     *
     * @param nullTerminated if true, add the number of bytes required for null-termination
     *
     * @throws BufferOverflowException if more than {@link Integer#MAX_VALUE} bytes are required to encode the text.
     */
    public static int memLengthUTF8(CharSequence value, boolean nullTerminated) {
        int len = value.length();
        int bytes = len + (nullTerminated ? 1 : 0); // start with 1:1

        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);

            if (c < 0x80) {
                // 1 input char -> 1 output byte
            } else {
                if (c < 0x800) {
                    // c <= 127: 0 (1 input char -> 1 output byte)
                    // c >= 128: 1 (1 input char -> 2 output bytes)
                    bytes += (0x7F - c) >>> 31;
                } else {
                    // non-high-surrogate: 1 input char -> 3 output bytes
                    // surrogate-pair: 2 input chars -> 4 output bytes
                    bytes += 2;
                    if (isHighSurrogate(c)) {
                        i++;
                    }
                }
                if (bytes < 0) {
                    throw new BufferOverflowException();
                }
            }
        }

        if (bytes < 0) {
            throw new BufferOverflowException();
        }

        return bytes;
    }

    /**
     * Returns a ByteBuffer containing the specified text UTF-16 encoded and null-terminated.
     *
     * @return the encoded text. The returned buffer must be deallocated manually with {@link #memFree}.
     *
     * @throws BufferOverflowException if more than {@link Integer#MAX_VALUE} bytes are required to encode the text.
     */
    public static ByteBuffer memUTF16(CharSequence text) {
        return memUTF16(text, true);
    }

    /** Like {@link #memUTF16(CharSequence) memUTF16}, but returns {@code null} if {@code text} is {@code null}. */
    public static @Nullable ByteBuffer memUTF16Safe(@Nullable CharSequence text) {
        return text == null ? null : memUTF16(text, true);
    }

    /**
     * Returns a ByteBuffer containing the specified text UTF-16 encoded and optionally null-terminated.
     *
     * @param nullTerminated if true, the text will be terminated with a '\0'.
     *
     * @return the encoded text. The returned buffer must be deallocated manually with {@link #memFree}.
     *
     * @throws BufferOverflowException if more than {@link Integer#MAX_VALUE} bytes are required to encode the text.
     */
    public static ByteBuffer memUTF16(CharSequence text, boolean nullTerminated) {
        int length = memLengthUTF16(text, nullTerminated);
        long target = nmemAlloc(length);
        if (CHECKS && target == NULL) {
            throw new OutOfMemoryError();
        }
        encodeUTF16Unsafe(text, nullTerminated, target);
        return wrapBufferByte(target, length);
    }

    /**
     * Like {@link #memUTF16(CharSequence, boolean) memUTF16}, but returns {@code null} if {@code text} is {@code null}.
     */
    public static @Nullable ByteBuffer memUTF16Safe(@Nullable CharSequence text, boolean nullTerminated) {
        return text == null ? null : memUTF16(text, nullTerminated);
    }

    /**
     * Encodes and optionally null-terminates the specified text using UTF-16 encoding. The encoded text is stored in
     * the specified {@link ByteBuffer}, at the current buffer position. The current buffer position is not modified by
     * this operation. The {@code target} buffer is assumed to have enough remaining space to store the encoded text.
     *
     * @param nullTerminated if true, the text will be terminated with a '\0'.
     *
     * @return the number of bytes of the encoded string
     *
     * @throws BufferOverflowException if more than {@code target.remaining()} bytes are required to encode the text.
     */
    public static int memUTF16(CharSequence text, boolean nullTerminated, ByteBuffer target) {
        if (target.remaining() < memLengthUTF16(text, nullTerminated)) {
            throw new BufferOverflowException();
        }
        long address = memAddress(target);
        return encodeUTF16Unsafe(text, nullTerminated, address);
    }

    /**
     * Encodes and optionally null-terminates the specified text using UTF-16 encoding. The encoded text is stored in
     * the specified {@link ByteBuffer} at the specified {@code position} offset. The current buffer position is not
     * modified by this operation. The {@code target} buffer is assumed to have enough remaining space to store the
     * encoded text.
     *
     * @param nullTerminated if true, the text will be terminated with a '\0'.
     *
     * @return the number of bytes of the encoded string
     *
     * @throws BufferOverflowException if more than {@code target.capacity() - offset} bytes are required to encode the
     *                                 text.
     */
    public static int memUTF16(CharSequence text, boolean nullTerminated, ByteBuffer target, int offset) {
        if (target.capacity() - offset < memLengthUTF16(text, nullTerminated)) {
            throw new BufferOverflowException();
        }
        long address = memAddress(target, offset);
        return encodeUTF16Unsafe(text, nullTerminated, address);
    }

    static int encodeUTF16Unsafe(CharSequence text, boolean nullTerminated, long target) {
        int p = 0, i = 0, len = text.length();

        while (i < len) {
            p = write16(target, p, text.charAt(i++));
        }

        if (nullTerminated) {
            p = write16(target, p, '\0');
        }

        return p;
    }

    /**
     * Returns the number of bytes required to encode the specified text in the UTF-16 encoding.
     *
     * @param nullTerminated if true, add the number of bytes required for null-termination
     */
    public static int memLengthUTF16(CharSequence value, boolean nullTerminated) {
        int len = value.length() + (nullTerminated ? 1 : 0);
        if (len < 0 || 0x3FFFFFFF < len) {
            throw new BufferOverflowException();
        }
        return len << 1;
    }

    private static int memLengthNT1(long address, int maxLength) {
        if (CHECKS) {
            check(address);
        }
        return BITS64 ? strlen64NT1(address, maxLength) : strlen32NT1(address, maxLength);
    }

    private static int strlen64NT1(long address, int maxLength) {
        int i = 0;

        if (8 <= maxLength) {
            int misalignment = (int) address & 7;
            if (misalignment != 0) {
                // Align to 8 bytes
                for (int len = 8 - misalignment; i < len; i++) {
                    if (MemoryUtil.memGetByte(address + i) == 0) {
                        return i;
                    }
                }
            }

            // Aligned longs for performance
            for (; i <= maxLength - 8; i += 8) {
                if (mathHasZeroByte(MemoryUtil.memGetLong(address + i))) {
                    break;
                }
            }
        }

        // Tail
        for (; i < maxLength; i++) {
            if (MemoryUtil.memGetByte(address + i) == 0) {
                break;
            }
        }

        return i;
    }

    private static int strlen32NT1(long address, int maxLength) {
        int i = 0;

        if (4 <= maxLength) {
            int misalignment = (int) address & 3;
            if (misalignment != 0) {
                // Align to 4 bytes
                for (int len = 4 - misalignment; i < len; i++) {
                    if (MemoryUtil.memGetByte(address + i) == 0) {
                        return i;
                    }
                }
            }

            // Aligned ints for performance
            for (; i <= maxLength - 4; i += 4) {
                if (mathHasZeroByte(MemoryUtil.memGetInt(address + i))) {
                    break;
                }
            }
        }

        // Tail
        for (; i < maxLength; i++) {
            if (MemoryUtil.memGetByte(address + i) == 0) {
                break;
            }
        }

        return i;
    }

    /**
     * Calculates the length, in bytes, of the null-terminated string that starts at the current position of the
     * specified buffer. A single \0 character will terminate the string. The returned length will NOT include the \0
     * byte.
     *
     * <p>
     * This method is useful for reading ASCII and UTF8 encoded text.
     * </p>
     */
    public static int memLengthNT1(ByteBuffer buffer) {
        return memLengthNT1(memAddress(buffer), buffer.remaining());
    }

    private static int memLengthNT2(long address, int maxLength) {
        if (CHECKS) {
            check(address);
        }
        return BITS64 ? strlen64NT2(address, maxLength) : strlen32NT2((int) address, maxLength);
    }

    private static int strlen64NT2(long address, int maxLength) {
        int i = 0;

        if (8 <= maxLength) {
            int misalignment = (int) address & 7;
            if (misalignment != 0) {
                // Align to 8 bytes
                for (int len = 8 - misalignment; i < len; i += 2) {
                    if (MemoryUtil.memGetShort(address + i) == 0) {
                        return i;
                    }
                }
            }

            // Aligned longs for performance
            for (; i <= maxLength - 8; i += 8) {
                if (mathHasZeroShort(MemoryUtil.memGetLong(address + i))) {
                    break;
                }
            }
        }

        // Tail
        for (; i < maxLength; i += 2) {
            if (MemoryUtil.memGetShort(address + i) == 0) {
                break;
            }
        }

        return i;
    }

    private static int strlen32NT2(long address, int maxLength) {
        int i = 0;

        if (4 <= maxLength) {
            int misalignment = (int) address & 3;
            if (misalignment != 0) {
                // Align to 4 bytes
                for (int len = 4 - misalignment; i < len; i += 2) {
                    if (MemoryUtil.memGetShort(address + i) == 0) {
                        return i;
                    }
                }
            }

            // Aligned longs for performance
            while (i <= maxLength - 4) {
                if (mathHasZeroShort(MemoryUtil.memGetInt(address + i))) {
                    break;
                }
                i += 4;
            }
        }

        // Tail
        for (; i < maxLength; i += 2) {
            if (MemoryUtil.memGetShort(address + i) == 0) {
                break;
            }
        }

        return i;
    }

    /**
     * Calculates the length, in bytes, of the null-terminated string that starts at the current position of the
     * specified buffer. Two \0 characters will terminate the string. The returned buffer will NOT include the \0 bytes.
     *
     * <p>
     * This method is useful for reading UTF16 encoded text.
     * </p>
     */
    public static int memLengthNT2(ByteBuffer buffer) {
        return memLengthNT2(memAddress(buffer), buffer.remaining());
    }

    /**
     * Creates a new direct ByteBuffer that starts at the specified memory address and has capacity equal to the
     * null-terminated string starting at that address. A single \0 character will terminate the string. The returned
     * buffer will NOT include the \0 byte.
     *
     * <p>
     * This method is useful for reading ASCII and UTF8 encoded text.
     * </p>
     */
    public static ByteBuffer memByteBufferNT1(long address) {
        return memByteBuffer(address, memLengthNT1(address, Integer.MAX_VALUE));
    }

    /**
     * Creates a new direct ByteBuffer that starts at the specified memory address and has capacity equal to the
     * null-terminated string starting at that address, up to a maximum of {@code maxLength} bytes. A single \0
     * character will terminate the string. The returned buffer will NOT include the \0 byte.
     *
     * <p>
     * This method is useful for reading ASCII and UTF8 encoded text.
     * </p>
     */
    public static ByteBuffer memByteBufferNT1(long address, int maxLength) {
        return memByteBuffer(address, memLengthNT1(address, maxLength));
    }

    /**
     * Like {@link #memByteBufferNT1(long) memByteBufferNT1}, but returns {@code null} if {@code address} is
     * {@link #NULL}.
     */
    public static @Nullable ByteBuffer memByteBufferNT1Safe(long address) {
        return address == NULL ? null : memByteBuffer(address, memLengthNT1(address, Integer.MAX_VALUE));
    }

    /**
     * Like {@link #memByteBufferNT1(long, int) memByteBufferNT1}, but returns {@code null} if {@code address} is
     * {@link #NULL}.
     */
    public static @Nullable ByteBuffer memByteBufferNT1Safe(long address, int maxLength) {
        return address == NULL ? null : memByteBuffer(address, memLengthNT1(address, maxLength));
    }

    /**
     * Creates a new direct ByteBuffer that starts at the specified memory address and has capacity equal to the
     * null-terminated string starting at that address. Two \0 characters will terminate the string. The returned buffer
     * will NOT include the \0 bytes.
     *
     * <p>
     * This method is useful for reading UTF16 encoded text.
     * </p>
     */
    public static ByteBuffer memByteBufferNT2(long address) {
        return memByteBufferNT2(address, Integer.MAX_VALUE - 1);
    }

    /**
     * Creates a new direct ByteBuffer that starts at the specified memory address and has capacity equal to the
     * null-terminated string starting at that address, up to a maximum of {@code maxLength} bytes. Two \0 characters
     * will terminate the string. The returned buffer will NOT include the \0 bytes.
     *
     * <p>
     * This method is useful for reading UTF16 encoded text.
     * </p>
     */
    public static ByteBuffer memByteBufferNT2(long address, int maxLength) {
        if (DEBUG) {
            if ((maxLength & 1) != 0) {
                throw new IllegalArgumentException("The maximum length must be an even number.");
            }
        }
        return memByteBuffer(address, memLengthNT2(address, maxLength));
    }

    /**
     * Like {@link #memByteBufferNT2(long) memByteBufferNT2}, but returns {@code null} if {@code address} is
     * {@link #NULL}.
     */
    public static @Nullable ByteBuffer memByteBufferNT2Safe(long address) {
        return address == NULL ? null : memByteBufferNT2(address, Integer.MAX_VALUE - 1);
    }

    /**
     * Like {@link #memByteBufferNT2(long, int) memByteBufferNT2}, but returns {@code null} if {@code address} is
     * {@link #NULL}.
     */
    public static @Nullable ByteBuffer memByteBufferNT2Safe(long address, int maxLength) {
        return address == NULL ? null : memByteBufferNT2(address, maxLength);
    }

    /**
     * Converts the null-terminated ASCII encoded string at the specified memory address to a {@link String}.
     */
    public static String memASCII(long address) {
        return memASCII(address, memLengthNT1(address, Integer.MAX_VALUE));
    }

    /**
     * Converts the ASCII encoded string at the specified memory address to a {@link String}.
     *
     * @param length  the number of bytes to decode
     */
    @SuppressWarnings("deprecation")
    public static String memASCII(long address, int length) {
        if (length <= 0) {
            return "";
        }

        byte[] ascii = length <= ARRAY_TLC_SIZE ? ARRAY_TLC_BYTE.get() : new byte[length];
        memByteBuffer(address, length).get(ascii, 0, length);
        return new String(ascii, 0, 0, length);
    }

    /**
     * Decodes the bytes with index {@code [position(), position()+remaining()}) in {@code buffer}, as an ASCII string.
     *
     * <p>
     * The current {@code position} and {@code limit} of the specified {@code buffer} are not affected by this
     * operation.
     * </p>
     */
    public static String memASCII(ByteBuffer buffer) {
        return memASCII(memAddress(buffer), buffer.remaining());
    }

    /** Like {@link #memASCII(long) memASCII}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable String memASCIISafe(long address) {
        return address == NULL ? null : memASCII(address, memLengthNT1(address, Integer.MAX_VALUE));
    }

    /** Like {@link #memASCII(long, int) memASCII}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable String memASCIISafe(long address, int length) {
        return address == NULL ? null : memASCII(address, length);
    }

    /** Like {@link #memASCII(ByteBuffer) memASCII}, but returns {@code null} if {@code buffer} is {@code null}. */
    public static @Nullable String memASCIISafe(@Nullable ByteBuffer buffer) {
        return buffer == null ? null : memASCII(memAddress(buffer), buffer.remaining());
    }

    /**
     * Decodes the bytes with index {@code [position(), position()+length}) in {@code buffer}, as an ASCII string.
     *
     * <p>
     * The current {@code position} and {@code limit} of the specified {@code buffer} are not affected by this
     * operation.
     * </p>
     *
     * @param length the number of bytes to decode
     */
    public static String memASCII(ByteBuffer buffer, int length) {
        return memASCII(memAddress(buffer), length);
    }

    /**
     * Decodes the bytes with index {@code [offset, offset+length}) in {@code buffer}, as an ASCII string.
     *
     * <p>
     * The current {@code position} and {@code limit} of the specified {@code buffer} are not affected by this
     * operation.
     * </p>
     *
     * @param length the number of bytes to decode
     */
    public static String memASCII(ByteBuffer buffer, int length, int offset) {
        return memASCII(memAddress(buffer, offset), length);
    }

    /**
     * Converts the null-terminated UTF-8 encoded string at the specified memory address to a {@link String}.
     */
    public static String memUTF8(long address) {
        return MultiReleaseTextDecoding.decodeUTF8(address, memLengthNT1(address, Integer.MAX_VALUE));
    }

    /**
     * Converts the UTF-8 encoded string at the specified memory address to a {@link String}.
     *
     * @param length  the number of bytes to decode
     */
    public static String memUTF8(long address, int length) {
        return MultiReleaseTextDecoding.decodeUTF8(address, length);
    }

    /**
     * Decodes the bytes with index {@code [position(), position()+remaining()}) in {@code buffer}, as a UTF-8 string.
     *
     * <p>
     * The current {@code position} and {@code limit} of the specified {@code buffer} are not affected by this
     * operation.
     * </p>
     */
    public static String memUTF8(ByteBuffer buffer) {
        return MultiReleaseTextDecoding.decodeUTF8(memAddress(buffer), buffer.remaining());
    }

    /** Like {@link #memUTF8(long) memUTF8}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable String memUTF8Safe(long address) {
        return address == NULL ? null
                : MultiReleaseTextDecoding.decodeUTF8(address, memLengthNT1(address, Integer.MAX_VALUE));
    }

    /** Like {@link #memUTF8(long, int) memUTF8}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable String memUTF8Safe(long address, int length) {
        return address == NULL ? null : MultiReleaseTextDecoding.decodeUTF8(address, length);
    }

    /** Like {@link #memUTF8(ByteBuffer) memUTF8}, but returns {@code null} if {@code buffer} is {@code null}. */
    public static @Nullable String memUTF8Safe(@Nullable ByteBuffer buffer) {
        return buffer == null ? null : MultiReleaseTextDecoding.decodeUTF8(memAddress(buffer), buffer.remaining());
    }

    /**
     * Decodes the bytes with index {@code [position(), position()+length}) in {@code buffer}, as a UTF-8 string.
     *
     * <p>
     * The current {@code position} and {@code limit} of the specified {@code buffer} are not affected by this
     * operation.
     * </p>
     *
     * @param length the number of bytes to decode
     */
    public static String memUTF8(ByteBuffer buffer, int length) {
        return MultiReleaseTextDecoding.decodeUTF8(memAddress(buffer), length);
    }

    /**
     * Decodes the bytes with index {@code [offset, offset+length}) in {@code buffer}, as a UTF-8 string.
     *
     * <p>
     * The current {@code position} and {@code limit} of the specified {@code buffer} are not affected by this
     * operation.
     * </p>
     *
     * @param length the number of bytes to decode
     */
    public static String memUTF8(ByteBuffer buffer, int length, int offset) {
        return MultiReleaseTextDecoding.decodeUTF8(memAddress(buffer, offset), length);
    }

    /**
     * Converts the null-terminated UTF-16 encoded string at the specified memory address to a {@link String}.
     */
    public static String memUTF16(long address) {
        return memUTF16(address, memLengthNT2(address, Integer.MAX_VALUE - 1) >> 1);
    }

    /**
     * Converts the UTF-16 encoded string at the specified memory address to a {@link String}.
     *
     * @param length  the number of characters to decode
     */
    public static String memUTF16(long address, int length) {
        if (length <= 0) {
            return "";
        }

        if (DEBUG) {
            // The implementation below does no codepoint validation.
            int len = length << 1;
            byte[] bytes = len <= ARRAY_TLC_SIZE ? ARRAY_TLC_BYTE.get() : new byte[len];
            memByteBuffer(address, len).get(bytes, 0, len);
            return new String(bytes, 0, len, UTF16);
        }

        char[] chars = length <= ARRAY_TLC_SIZE ? ARRAY_TLC_CHAR.get() : new char[length];
        memCharBuffer(address, length).get(chars, 0, length);
        return new String(chars, 0, length);
    }

    /**
     * Decodes the bytes with index {@code [position(), position()+remaining()}) in {@code buffer}, as a UTF-16 string.
     *
     * <p>
     * The current {@code position} and {@code limit} of the specified {@code buffer} are not affected by this
     * operation.
     * </p>
     */
    public static String memUTF16(ByteBuffer buffer) {
        return memUTF16(memAddress(buffer), buffer.remaining() >> 1);
    }

    /** Like {@link #memUTF16(long) memUTF16}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable String memUTF16Safe(long address) {
        return address == NULL ? null : memUTF16(address, memLengthNT2(address, Integer.MAX_VALUE - 1) >> 1);
    }

    /** Like {@link #memUTF16(long, int) memUTF16}, but returns {@code null} if {@code address} is {@link #NULL}. */
    public static @Nullable String memUTF16Safe(long address, int length) {
        return address == NULL ? null : memUTF16(address, length);
    }

    /** Like {@link #memUTF16(ByteBuffer) memUTF16}, but returns {@code null} if {@code buffer} is {@code null}. */
    public static @Nullable String memUTF16Safe(@Nullable ByteBuffer buffer) {
        return buffer == null ? null : memUTF16(memAddress(buffer), buffer.remaining() >> 1);
    }

    /**
     * Decodes the bytes with index {@code [position(), position()+(length*2)}) in {@code buffer}, as a UTF-16 string.
     *
     * <p>
     * The current {@code position} and {@code limit} of the specified {@code buffer} are not affected by this
     * operation.
     * </p>
     *
     * @param length the number of characters to decode
     */
    public static String memUTF16(ByteBuffer buffer, int length) {
        return memUTF16(memAddress(buffer), length);
    }

    /**
     * Decodes the bytes with index {@code [offset, offset+(length*2)}) in {@code buffer}, as a UTF-16 string.
     *
     * <p>
     * The current {@code position} and {@code limit} of the specified {@code buffer} are not affected by this
     * operation.
     * </p>
     *
     * @param length the number of characters to decode
     * @param offset the offset at which to start decoding, in bytes.
     */
    public static String memUTF16(ByteBuffer buffer, int length, int offset) {
        return memUTF16(memAddress(buffer, offset), length);
    }

    static ByteBuffer wrapBufferByte(long address, int capacity) {
        return MemoryUtil.memByteBuffer(address, capacity);
    }

    static ShortBuffer wrapBufferShort(long address, int capacity) {
        return MemoryUtil.memShortBuffer(address, capacity);
    }

    static CharBuffer wrapBufferChar(long address, int capacity) {
        return MemoryUtil.memCharBuffer(address, capacity);
    }

    static IntBuffer wrapBufferInt(long address, int capacity) {
        return MemoryUtil.memIntBuffer(address, capacity);
    }

    static LongBuffer wrapBufferLong(long address, int capacity) {
        return MemoryUtil.memLongBuffer(address, capacity);
    }

    static FloatBuffer wrapBufferFloat(long address, int capacity) {
        return MemoryUtil.memFloatBuffer(address, capacity);
    }

    static DoubleBuffer wrapBufferDouble(long address, int capacity) {
        return MemoryUtil.memDoubleBuffer(address, capacity);
    }

}
