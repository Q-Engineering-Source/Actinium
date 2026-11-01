package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VAOManagerDeriveVertexFlagsTest {
    private final VAOManager vaos = new VAOManager();

    @BeforeEach
    void setUp() {
        vaos.init(0);
    }

    @Test
    void freshVaoDerivesNoFlags() {
        vaos.onBindVertexArrayPre(1);
        assertEquals(0, vaos.currentClientArrayVertexFlags());
    }

    @Test
    void noColorAttribDerivesNoColorBit() {
        vaos.onBindVertexArrayPre(1);
        vaos.setEnabled(Usage.POSITION.getAttributeLocation(), true);
        vaos.setEnabled(Usage.PRIMARY_UV.getAttributeLocation(), true);
        vaos.setEnabled(Usage.NORMAL.getAttributeLocation(), true);

        assertEquals(VertexFlags.TEXTURE_BIT | VertexFlags.NORMAL_BIT, vaos.currentClientArrayVertexFlags());
    }

    @Test
    void colorAttribDerivesColorBit() {
        vaos.onBindVertexArrayPre(1);
        vaos.setEnabled(Usage.POSITION.getAttributeLocation(), true);
        vaos.setEnabled(Usage.PRIMARY_UV.getAttributeLocation(), true);
        vaos.setEnabled(Usage.COLOR.getAttributeLocation(), true);

        assertEquals(VertexFlags.TEXTURE_BIT | VertexFlags.COLOR_BIT, vaos.currentClientArrayVertexFlags());
    }

    @Test
    void derivedMaskIsPerVao() {
        vaos.onBindVertexArrayPre(1);
        vaos.setEnabled(Usage.POSITION.getAttributeLocation(), true);
        vaos.setEnabled(Usage.SECONDARY_UV.getAttributeLocation(), true);
        assertEquals(VertexFlags.BRIGHTNESS_BIT, vaos.currentClientArrayVertexFlags());

        vaos.onBindVertexArrayPre(2);
        assertEquals(0, vaos.currentClientArrayVertexFlags());
    }

    @Test
    void disablingAttribDropsItsBit() {
        vaos.onBindVertexArrayPre(1);
        vaos.setEnabled(Usage.COLOR.getAttributeLocation(), true);
        assertEquals(VertexFlags.COLOR_BIT, vaos.currentClientArrayVertexFlags());

        vaos.setEnabled(Usage.COLOR.getAttributeLocation(), false);
        assertEquals(0, vaos.currentClientArrayVertexFlags());
    }

    @Test
    void clientPointerReadsFromCurrentPositionAcrossVertexStride() {
        final int position = 8;
        final int stride = 16;
        final ByteBuffer source = ByteBuffer.allocateDirect(position + stride * 2).order(ByteOrder.nativeOrder());
        source.putFloat(0, -100.0f);
        source.putFloat(position, 10.0f);
        source.putFloat(position + 4, 11.0f);
        source.putFloat(position + 8, 12.0f);
        source.putFloat(position + stride, 20.0f);
        source.putFloat(position + stride + 4, 21.0f);
        source.putFloat(position + stride + 8, 22.0f);
        source.limit(position + 12);
        source.position(position);

        final int location = Usage.POSITION.getAttributeLocation();
        vaos.setAttribute(location, 3, GL11.GL_FLOAT, false, stride, source, 0);
        final VAOManager.Attrib attrib = vaos.attrib(location);

        assertEquals(position, source.position());
        assertEquals(0, attrib.clientPointer.position());
        assertEquals(source.capacity() - position, attrib.clientPointer.capacity());
        assertEquals(ByteOrder.nativeOrder(), attrib.clientPointer.order());
        assertEquals(10.0f, attrib.readComponent(attrib.clientPointer, 0, 0));
        assertEquals(12.0f, attrib.readComponent(attrib.clientPointer, 0, 2));
        assertEquals(20.0f, attrib.readComponent(attrib.clientPointer, stride, 0));
        assertEquals(22.0f, attrib.readComponent(attrib.clientPointer, stride, 2));
    }
}
