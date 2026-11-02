package com.gtnewhorizons.angelica.glsm.states;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PixelStoreStateTest {

    private static final PixelStoreState BASE = new PixelStoreState(4, 16, 1, 2, 8, 3, false, false);

    @Test
    void unpackPnamesChangeOnlyTheirOwnField() {
        assertEquals(new PixelStoreState(8, 16, 1, 2, 8, 3, false, false), BASE.with(GL11.GL_UNPACK_ALIGNMENT, 8));
        assertEquals(new PixelStoreState(4, 32, 1, 2, 8, 3, false, false), BASE.with(GL11.GL_UNPACK_ROW_LENGTH, 32));
        assertEquals(new PixelStoreState(4, 16, 5, 2, 8, 3, false, false), BASE.with(GL11.GL_UNPACK_SKIP_ROWS, 5));
        assertEquals(new PixelStoreState(4, 16, 1, 7, 8, 3, false, false), BASE.with(GL11.GL_UNPACK_SKIP_PIXELS, 7));
        assertEquals(new PixelStoreState(4, 16, 1, 2, 64, 3, false, false), BASE.with(GL12.GL_UNPACK_IMAGE_HEIGHT, 64));
        assertEquals(new PixelStoreState(4, 16, 1, 2, 8, 9, false, false), BASE.with(GL12.GL_UNPACK_SKIP_IMAGES, 9));
        assertEquals(new PixelStoreState(4, 16, 1, 2, 8, 3, true, false), BASE.with(GL11.GL_UNPACK_SWAP_BYTES, 1));
        assertEquals(new PixelStoreState(4, 16, 1, 2, 8, 3, false, true), BASE.with(GL11.GL_UNPACK_LSB_FIRST, 1));
    }

    @Test
    void packPnamesChangeOnlyTheirOwnField() {
        assertEquals(new PixelStoreState(8, 16, 1, 2, 8, 3, false, false), BASE.with(GL11.GL_PACK_ALIGNMENT, 8));
        assertEquals(new PixelStoreState(4, 32, 1, 2, 8, 3, false, false), BASE.with(GL11.GL_PACK_ROW_LENGTH, 32));
        assertEquals(new PixelStoreState(4, 16, 5, 2, 8, 3, false, false), BASE.with(GL11.GL_PACK_SKIP_ROWS, 5));
        assertEquals(new PixelStoreState(4, 16, 1, 7, 8, 3, false, false), BASE.with(GL11.GL_PACK_SKIP_PIXELS, 7));
        assertEquals(new PixelStoreState(4, 16, 1, 2, 64, 3, false, false), BASE.with(GL12.GL_PACK_IMAGE_HEIGHT, 64));
        assertEquals(new PixelStoreState(4, 16, 1, 2, 8, 9, false, false), BASE.with(GL12.GL_PACK_SKIP_IMAGES, 9));
        assertEquals(new PixelStoreState(4, 16, 1, 2, 8, 3, true, false), BASE.with(GL11.GL_PACK_SWAP_BYTES, 1));
        assertEquals(new PixelStoreState(4, 16, 1, 2, 8, 3, false, true), BASE.with(GL11.GL_PACK_LSB_FIRST, 1));
    }

    @Test
    void invalidValuesKeepTheSameInstance() {
        for (final int pname : new int[] { GL11.GL_UNPACK_ALIGNMENT, GL11.GL_PACK_ALIGNMENT }) {
            assertSame(BASE, BASE.with(pname, 3));
            assertSame(BASE, BASE.with(pname, 0));
            assertSame(BASE, BASE.with(pname, 16));
        }
        for (final int pname : new int[] {
            GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_PACK_ROW_LENGTH,
            GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_PACK_SKIP_ROWS,
            GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_PACK_SKIP_PIXELS,
            GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_PACK_IMAGE_HEIGHT,
            GL12.GL_UNPACK_SKIP_IMAGES, GL12.GL_PACK_SKIP_IMAGES,
        }) {
            assertSame(BASE, BASE.with(pname, -1));
        }
    }

    @Test
    void unchangedValuesKeepTheSameInstance() {
        for (final int pname : new int[] { GL11.GL_UNPACK_ALIGNMENT, GL11.GL_PACK_ALIGNMENT }) {
            assertSame(BASE, BASE.with(pname, BASE.alignment()));
        }
        for (final int pname : new int[] { GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_PACK_ROW_LENGTH }) {
            assertSame(BASE, BASE.with(pname, BASE.rowLength()));
        }
        for (final int pname : new int[] { GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_PACK_SKIP_ROWS }) {
            assertSame(BASE, BASE.with(pname, BASE.skipRows()));
        }
        for (final int pname : new int[] { GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_PACK_SKIP_PIXELS }) {
            assertSame(BASE, BASE.with(pname, BASE.skipPixels()));
        }
        for (final int pname : new int[] { GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_PACK_IMAGE_HEIGHT }) {
            assertSame(BASE, BASE.with(pname, BASE.imageHeight()));
        }
        for (final int pname : new int[] { GL12.GL_UNPACK_SKIP_IMAGES, GL12.GL_PACK_SKIP_IMAGES }) {
            assertSame(BASE, BASE.with(pname, BASE.skipImages()));
        }
        for (final int pname : new int[] { GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_PACK_SWAP_BYTES }) {
            assertSame(BASE, BASE.with(pname, 0));
        }
        for (final int pname : new int[] { GL11.GL_UNPACK_LSB_FIRST, GL11.GL_PACK_LSB_FIRST }) {
            assertSame(BASE, BASE.with(pname, 0));
        }

        final PixelStoreState flagsOn = BASE.with(GL11.GL_UNPACK_SWAP_BYTES, 1).with(GL11.GL_PACK_LSB_FIRST, 1);
        assertSame(flagsOn, flagsOn.with(GL11.GL_UNPACK_SWAP_BYTES, 3));
        assertSame(flagsOn, flagsOn.with(GL11.GL_PACK_LSB_FIRST, 7));
    }

    @Test
    void booleanPnamesTreatAnyNonZeroAsTrue() {
        for (final int pname : new int[] { GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_PACK_SWAP_BYTES }) {
            assertTrue(BASE.with(pname, 1).swapBytes());
            assertTrue(BASE.with(pname, 2).swapBytes());
            assertTrue(BASE.with(pname, -1).swapBytes());
        }
        for (final int pname : new int[] { GL11.GL_UNPACK_LSB_FIRST, GL11.GL_PACK_LSB_FIRST }) {
            assertTrue(BASE.with(pname, 1).lsbFirst());
            assertTrue(BASE.with(pname, 2).lsbFirst());
            assertTrue(BASE.with(pname, -1).lsbFirst());
        }

        final PixelStoreState swapBytesOn = BASE.with(GL11.GL_UNPACK_SWAP_BYTES, 2);
        final PixelStoreState swapBytesOff = swapBytesOn.with(GL11.GL_PACK_SWAP_BYTES, 0);
        assertFalse(swapBytesOff.swapBytes());
        assertEquals(BASE, swapBytesOff);
    }

    @Test
    void unknownPnameKeepsTheSameInstance() {
        assertSame(BASE, BASE.with(0x1234, 1));
        assertSame(BASE, BASE.with(0, 4));
    }

    @Test
    void isPackSeparatesTheTwoFamilies() {
        final int[] pack = {
            GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SKIP_PIXELS,
            GL12.GL_PACK_IMAGE_HEIGHT, GL12.GL_PACK_SKIP_IMAGES, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST,
        };
        final int[] unpack = {
            GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SKIP_PIXELS,
            GL12.GL_UNPACK_IMAGE_HEIGHT, GL12.GL_UNPACK_SKIP_IMAGES, GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST,
        };

        for (final int pname : pack) {
            assertTrue(PixelStoreState.isPack(pname), "expected pack family for pname 0x" + Integer.toHexString(pname));
        }
        for (final int pname : unpack) {
            assertFalse(PixelStoreState.isPack(pname), "expected unpack family for pname 0x" + Integer.toHexString(pname));
        }
        assertFalse(PixelStoreState.isPack(0x1234));
    }
}
