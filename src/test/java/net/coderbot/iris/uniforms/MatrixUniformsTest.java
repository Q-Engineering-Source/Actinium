package net.coderbot.iris.uniforms;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatrixUniformsTest {
    @Test
    void previousMatrixAdvancesOnlyOncePerFrame() {
        SystemTimeUniforms.COUNTER.reset();
        try {
            final Matrix4f first = new Matrix4f().translation(1.0f, 2.0f, 3.0f);
            final Matrix4f second = new Matrix4f().translation(4.0f, 5.0f, 6.0f);
            final AtomicReference<Matrix4f> current = new AtomicReference<>(first);
            final MatrixUniforms.Previous previous = new MatrixUniforms.Previous(current::get);

            assertEquals(new Matrix4f(), previous.get());
            assertEquals(new Matrix4f(), previous.get());

            current.set(second);
            assertEquals(new Matrix4f(), previous.get());

            SystemTimeUniforms.COUNTER.beginFrame();
            assertEquals(first, previous.get());
            assertEquals(first, previous.get());

            SystemTimeUniforms.COUNTER.beginFrame();
            assertEquals(second, previous.get());
        } finally {
            SystemTimeUniforms.COUNTER.reset();
        }
    }
}
