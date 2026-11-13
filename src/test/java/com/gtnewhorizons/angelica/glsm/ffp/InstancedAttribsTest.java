package com.gtnewhorizons.angelica.glsm.ffp;

import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstancedAttribsTest {

    @Test
    void writesTheTransformTintOverlayAndEntityTail() {
        final ByteBuffer data = BufferUtils.createByteBuffer(InstancedAttribs.STRIDE);
        final float[] matrix = {
            1.0F, 0.0F, 0.0F, 0.0F,
            0.0F, 1.0F, 0.0F, 0.0F,
            0.0F, 0.0F, 1.0F, 0.0F,
            3.0F, 4.0F, 5.0F, 1.0F
        };
        InstancedAttribs.writeHead(MemoryUtil.memAddress(data), matrix, 0, 0x11223344, 0x55667788,
            InstancedAttribs.packEntityInfo(12, 34, 56));

        assertEquals(1.0F, data.getFloat(InstancedAttribs.OFFSET_ROWS));
        assertEquals(3.0F, data.getFloat(12));
        assertEquals(4.0F, data.getFloat(28));
        assertEquals(5.0F, data.getFloat(44));
        assertEquals(0x11223344, data.getInt(InstancedAttribs.OFFSET_COLOR));
        assertEquals(0x55667788, data.getInt(InstancedAttribs.OFFSET_OVERLAY));
        assertEquals(12, InstancedAttribs.unpackId(data.getLong(InstancedAttribs.OFFSET_ENTITY), 0));
        assertEquals(34, InstancedAttribs.unpackId(data.getLong(InstancedAttribs.OFFSET_ENTITY), 16));
        assertEquals(56, InstancedAttribs.unpackId(data.getLong(InstancedAttribs.OFFSET_ENTITY), 32));
    }

    @Test
    void writesBothLightmapChannelsAsFloats() {
        final ByteBuffer data = BufferUtils.createByteBuffer(InstancedAttribs.STRIDE);
        InstancedAttribs.writeLightmap(MemoryUtil.memAddress(data) + InstancedAttribs.OFFSET_LIGHTMAP,
            0x00F000A0);

        assertEquals(0x00A0, data.getFloat(InstancedAttribs.OFFSET_LIGHTMAP));
        assertEquals(0x00F0, data.getFloat(InstancedAttribs.OFFSET_LIGHTMAP + 4));
    }

    @Test
    void entityIdsRoundTripAndReserveMinusOneForMissingOwners() {
        final long ids = InstancedAttribs.packEntityInfo(-1, 200, 300);

        assertEquals(-1, InstancedAttribs.unpackId(ids, 0));
        assertEquals(200, InstancedAttribs.unpackId(ids, 16));
        assertEquals(300, InstancedAttribs.unpackId(ids, 32));
        assertEquals(-1, InstancedAttribs.unpackId(InstancedAttribs.DEFAULT_ENTITY_IDS, 0));
    }
}
