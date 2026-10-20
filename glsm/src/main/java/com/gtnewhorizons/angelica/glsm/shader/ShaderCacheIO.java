package com.gtnewhorizons.angelica.glsm.shader;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

/** Shared encoders and small helpers for the shader disk cache format. */
public final class ShaderCacheIO {
    /** Prevents constructing a stateless helper. */
    private ShaderCacheIO() {}

    /** Copies the remaining bytes without changing the caller's buffer position. */
    public static byte[] toHeap(ByteBuffer buffer) {
        final byte[] heap = new byte[buffer.remaining()];
        buffer.duplicate().get(heap);
        return heap;
    }

    /** Allocates a native buffer containing the supplied cache payload. */
    public static ByteBuffer toNative(byte[] data) {
        final ByteBuffer buffer = MemoryUtil.memAlloc(data.length);
        buffer.put(data).flip();
        return buffer;
    }

    /** Inserts a value at the front of an access ordered map and drops its oldest values. */
    public static <K, V> void lruPut(Object2ObjectLinkedOpenHashMap<K, V> map, K key, V value, int max) {
        synchronized (map) {
            map.putAndMoveToFirst(key, value);
            while (map.size() > max) map.removeLast();
        }
    }

    /** Writes a nullable UTF-8 string as a signed length followed by its bytes. */
    public static void writeStr(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(utf8.length);
        output.write(utf8);
    }

    /** Reads a nullable UTF-8 string and rejects lengths outside the remaining payload. */
    public static String readStr(ByteBuffer input) {
        final int length = input.getInt();
        if (length == -1) return null;
        if (length < 0 || length > input.remaining()) throw new BufferUnderflowException();
        final byte[] utf8 = new byte[length];
        input.get(utf8);
        return new String(utf8, StandardCharsets.UTF_8);
    }

    /** Reads a nonnegative item count that cannot exceed the remaining payload size. */
    public static int readCount(ByteBuffer input) {
        final int count = input.getInt();
        if (count < 0 || count > input.remaining()) throw new BufferUnderflowException();
        return count;
    }

    /** Writes a length-prefixed byte array. */
    public static void writeBytes(DataOutputStream output, byte[] data) throws IOException {
        output.writeInt(data.length);
        output.write(data);
    }

    /** Reads a length-prefixed byte array. */
    public static byte[] readBytes(ByteBuffer input) {
        final byte[] data = new byte[readCount(input)];
        input.get(data);
        return data;
    }

    /** Writes a length-prefixed integer array. */
    public static void writeInts(DataOutputStream output, int[] values) throws IOException {
        output.writeInt(values.length);
        for (int value : values) output.writeInt(value);
    }

    /** Reads a length-prefixed integer array. */
    public static int[] readInts(ByteBuffer input) {
        final int count = readCount(input);
        if (count > input.remaining() / Integer.BYTES) throw new BufferUnderflowException();
        final int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = input.getInt();
        return values;
    }

    /** Writes a set in stable sorted order so equivalent sets have identical payloads. */
    public static void writeSortedStrs(DataOutputStream output, Set<String> values) throws IOException {
        final Set<String> sorted = values == null ? Set.of() : new TreeSet<>(values);
        output.writeInt(sorted.size());
        for (String value : sorted) writeStr(output, value);
    }

    /** Reads a set of nullable strings while retaining deterministic iteration order. */
    public static Set<String> readStrSet(ByteBuffer input) {
        final String[] values = new String[readCount(input)];
        for (int i = 0; i < values.length; i++) values[i] = readStr(input);
        return Set.copyOf(Arrays.asList(values));
    }

    /** Identifies an LWJGL shared library version for cache keys. */
    public static String libraryId(SharedLibrary library) {
        final String path = library.getPath();
        return Version.getVersion() + '|' + library.getName() + '|' + (path == null ? -1 : new File(path).length());
    }
}
