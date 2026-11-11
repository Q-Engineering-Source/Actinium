package com.gtnewhorizons.angelica.sdlgpu.util;

import org.lwjgl.system.MemoryUtil;

/** Native memory operations used by upload, readback and uniform staging. */
public final class MemoryAccess {
    private MemoryAccess() {}

    public static byte getByte(long address) { return MemoryUtil.memGetByte(address); }
    public static void putByte(long address, byte value) { MemoryUtil.memPutByte(address, value); }

    public static short getShort(long address) { return MemoryUtil.memGetShort(address); }
    public static void putShort(long address, short value) { MemoryUtil.memPutShort(address, value); }

    public static int getInt(long address) { return MemoryUtil.memGetInt(address); }
    public static void putInt(long address, int value) { MemoryUtil.memPutInt(address, value); }

    public static float getFloat(long address) { return MemoryUtil.memGetFloat(address); }
    public static void putFloat(long address, float value) { MemoryUtil.memPutFloat(address, value); }

    public static long getLong(long address) { return MemoryUtil.memGetLong(address); }
    public static void putLong(long address, long value) { MemoryUtil.memPutLong(address, value); }

    public static long getAddress(long address) { return MemoryUtil.memGetAddress(address); }
    public static void putAddress(long address, long value) { MemoryUtil.memPutAddress(address, value); }

    public static void copyFloatsToAddr(float[] source, int sourceOffset, long destination, int count) {
        for (int i = 0; i < count; i++) {
            MemoryUtil.memPutFloat(destination + (long) i * Float.BYTES, source[sourceOffset + i]);
        }
    }
}
