package com.gtnewhorizons.angelica.rendering.tesr;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the shared eight-int view of the vanilla vertex buffer.
 *
 * <p>Vanilla stores each vertex in the element order and stride of the active format. The batch
 * has to read the same attributes from fixed slots, so the packer walks the format instead of
 * copying bytes: {@code POSITION_COLOR} has no texture coordinate, and a byte copy would move its
 * colour into slot 3 where every reader expects the texture coordinate.</p>
 */
class SharedVertexLayoutTest {

    private static int[] pack(BufferBuilder buffer) {
        final int[] packed = new int[buffer.getVertexCount() * SharedVertexLayout.VERTEX_INTS];
        SharedVertexLayout.pack(buffer.getByteBuffer(), buffer.getVertexFormat(), buffer.getVertexCount(), packed);
        return packed;
    }

    @Test
    void packsPositionTextureNormalIntoTheSharedSlots() {
        final BufferBuilder buffer = new BufferBuilder(256);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buffer.pos(1.0, 2.0, 3.0).tex(0.25, 0.75).normal(0.0F, 1.0F, 0.0F).endVertex();
        buffer.pos(4.0, 5.0, 6.0).tex(0.5, 0.625).normal(-1.0F, 0.0F, 0.0F).endVertex();

        final int[] packed = pack(buffer);

        assertEquals(Float.floatToRawIntBits(1.0F), packed[0]);
        assertEquals(Float.floatToRawIntBits(2.0F), packed[1]);
        assertEquals(Float.floatToRawIntBits(3.0F), packed[2]);
        assertEquals(Float.floatToRawIntBits(0.25F), packed[3]);
        assertEquals(Float.floatToRawIntBits(0.75F), packed[4]);
        assertEquals(Float.floatToRawIntBits(4.0F), packed[SharedVertexLayout.VERTEX_INTS]);
        assertEquals(Float.floatToRawIntBits(0.5F), packed[SharedVertexLayout.VERTEX_INTS + 3]);
        assertTrue(SharedVertexLayout.has(buffer.getVertexFormat(), VertexFormatElement.EnumUsage.UV, 0));
        assertTrue(SharedVertexLayout.has(buffer.getVertexFormat(), VertexFormatElement.EnumUsage.NORMAL, 0));
        assertFalse(SharedVertexLayout.has(buffer.getVertexFormat(), VertexFormatElement.EnumUsage.COLOR, 0));
    }

    @Test
    void unpacksTheNormalIntoThreeSignedBytes() {
        final BufferBuilder buffer = new BufferBuilder(256);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buffer.pos(0.0, 0.0, 0.0).tex(0.0, 0.0).normal(1.0F, -1.0F, 0.0F).endVertex();

        final int normal = pack(buffer)[6];

        assertEquals(127, (byte) (normal & 0xFF));
        assertEquals(-127, (byte) (normal >> 8 & 0xFF));
        assertEquals(0, (byte) (normal >> 16 & 0xFF));
    }

    @Test
    void packsTheLightmapElementIntoOneInt() {
        final BufferBuilder buffer = new BufferBuilder(256);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
        // lightmap(skyLight, blockLight): the element stores the block light in the low short
        buffer.pos(0.0, 0.0, 0.0).color(255, 128, 64, 32).tex(0.0, 0.0).lightmap(192, 240).endVertex();

        final int[] packed = pack(buffer);

        assertTrue(SharedVertexLayout.has(buffer.getVertexFormat(), VertexFormatElement.EnumUsage.COLOR, 0));
        assertTrue(SharedVertexLayout.has(buffer.getVertexFormat(), VertexFormatElement.EnumUsage.UV, 1));
        assertEquals(240 | 192 << 16, packed[7]);
        // 0xAABBGGRR: the byte order the colour element writes, read back as a little-endian int
        assertEquals(32 << 24 | 64 << 16 | 128 << 8 | 255, packed[5]);
    }

    @Test
    void keepsALayoutWithFewerElementsInTheSameSlots() {
        final BufferBuilder buffer = new BufferBuilder(256);
        final VertexFormat format = DefaultVertexFormats.POSITION_COLOR;
        buffer.begin(GL11.GL_QUADS, format);
        buffer.pos(7.0, 8.0, 9.0).color(16, 32, 48, 64).endVertex();

        final int[] packed = pack(buffer);

        assertEquals(Float.floatToRawIntBits(7.0F), packed[0]);
        assertEquals(Float.floatToRawIntBits(8.0F), packed[1]);
        assertEquals(Float.floatToRawIntBits(9.0F), packed[2]);
        assertEquals(64 << 24 | 48 << 16 | 32 << 8 | 16, packed[5]);
        // A byte copy of this format would place the colour in slot 3, where readers take the texture.
        assertNotEquals(64 << 24 | 48 << 16 | 32 << 8 | 16, packed[3]);
        assertFalse(SharedVertexLayout.has(format, VertexFormatElement.EnumUsage.UV, 0));
    }
}
