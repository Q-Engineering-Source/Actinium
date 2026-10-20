package net.coderbot.iris.gl.uniform;

import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.joml.Vector4i;
import org.joml.Vector4ic;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Push-notified uniforms re-register their listener whenever a program updates them, so each
 * uniform hands the notifier the same listener object instead of allocating a new one per
 * registration. The values below never change, so the updates stay clear of the GL backend.
 */
class UniformUpdateListenerReuseTest {
    private final RecordingHolder holder = new RecordingHolder();

    @Test
    void floatUniformFromFloatSupplierReusesItsListener() {
        final AtomicInteger reads = new AtomicInteger();
        final FloatSupplier value = () -> {
            reads.incrementAndGet();
            return 0f;
        };

        assertReusesItsListener(notifier -> holder.uniform1f("frameTime", value, notifier), reads);
    }

    @Test
    void floatUniformFromIntSupplierReusesItsListener() {
        final AtomicInteger reads = new AtomicInteger();
        final IntSupplier value = () -> {
            reads.incrementAndGet();
            return 0;
        };

        assertReusesItsListener(notifier -> holder.uniform1f("frameCounter", value, notifier), reads);
    }

    @Test
    void floatUniformFromDoubleSupplierReusesItsListener() {
        final AtomicInteger reads = new AtomicInteger();
        final DoubleSupplier value = () -> {
            reads.incrementAndGet();
            return 0d;
        };

        assertReusesItsListener(notifier -> holder.uniform1f("frameTimeCounter", value, notifier), reads);
    }

    @Test
    void intUniformReusesItsListener() {
        final AtomicInteger reads = new AtomicInteger();
        final IntSupplier value = () -> {
            reads.incrementAndGet();
            return 0;
        };

        assertReusesItsListener(notifier -> holder.uniform1i("renderStage", value, notifier), reads);
    }

    @Test
    void vector2IntegerUniformReusesItsListener() {
        final AtomicInteger reads = new AtomicInteger();
        final Vector2ic zero = new Vector2i();
        final Supplier<Vector2ic> value = () -> {
            reads.incrementAndGet();
            return zero;
        };

        assertReusesItsListener(notifier -> holder.uniform2i("eyeAltitude", value, notifier), reads);
    }

    @Test
    void vector3IntegerUniformReusesItsListener() {
        final AtomicInteger reads = new AtomicInteger();
        final Vector3ic zero = new Vector3i();
        final Supplier<Vector3ic> value = () -> {
            reads.incrementAndGet();
            return zero;
        };

        assertReusesItsListener(notifier -> holder.uniform3i("cameraPositionInt", value, notifier), reads);
    }

    @Test
    void vector4UniformReusesItsListener() {
        final AtomicInteger reads = new AtomicInteger();
        final Vector4fc zero = new Vector4f();
        final Supplier<Vector4fc> value = () -> {
            reads.incrementAndGet();
            return zero;
        };

        assertReusesItsListener(notifier -> holder.uniform4f("entityColor", value, notifier), reads);
    }

    @Test
    void vector4ArrayUniformReusesItsListener() {
        final AtomicInteger reads = new AtomicInteger();
        final float[] zero = new float[4];
        final Supplier<float[]> value = () -> {
            reads.incrementAndGet();
            return zero;
        };

        assertReusesItsListener(notifier -> holder.uniform4fArray("entityColor", value, notifier), reads);
    }

    @Test
    void vector4IntegerUniformReusesItsListener() {
        final AtomicInteger reads = new AtomicInteger();
        final Vector4ic zero = new Vector4i();
        final Supplier<Vector4ic> value = () -> {
            reads.incrementAndGet();
            return zero;
        };

        assertReusesItsListener(notifier -> holder.uniform4i("lightLevel", value, notifier), reads);
    }

    private void assertReusesItsListener(Consumer<ValueUpdateNotifier> registration, AtomicInteger reads) {
        final RecordingNotifier notifier = new RecordingNotifier();
        registration.accept(notifier);

        final List<Uniform> uniforms = this.holder.getUniforms();
        assertEquals(1, uniforms.size(), "the registration must reach the holder");
        final Uniform uniform = uniforms.get(0);

        uniform.update();
        uniform.update();
        assertEquals(2, reads.get(), "every update reads the value once");

        final Runnable listener = notifier.assertReusedListener();
        listener.run();
        assertEquals(3, reads.get(), "the registered listener drives the same per-value update path");
    }

    /** Notifier that records what the uniform registers, so the listener identity is observable. */
    private static final class RecordingNotifier implements ValueUpdateNotifier {
        private final List<Runnable> listeners = new ArrayList<>();

        @Override
        public void setListener(Runnable listener) {
            this.listeners.add(listener);
        }

        private Runnable assertReusedListener() {
            assertEquals(2, this.listeners.size(), "every update registers exactly one listener");

            final Runnable first = this.listeners.get(0);
            assertNotNull(first, "a push-notified uniform registers a real listener");
            assertSame(first, this.listeners.get(1), "the second registration must reuse the same listener object");

            return first;
        }
    }

    /** Holder that collects the uniforms the interface defaults build for it. */
    private static final class RecordingHolder implements DynamicLocationalUniformHolder {
        private final List<Uniform> uniforms = new ArrayList<>();
        private int nextLocation = 1;

        private List<Uniform> getUniforms() {
            return this.uniforms;
        }

        @Override
        public DynamicLocationalUniformHolder addDynamicUniform(Uniform uniform, ValueUpdateNotifier notifier) {
            this.uniforms.add(uniform);
            return this;
        }

        @Override
        public LocationalUniformHolder addUniform(UniformUpdateFrequency updateFrequency, Uniform uniform) {
            this.uniforms.add(uniform);
            return this;
        }

        @Override
        public OptionalInt location(String name, UniformType type) {
            return OptionalInt.of(this.nextLocation++);
        }

        @Override
        public UniformHolder externallyManagedUniform(String name, UniformType type) {
            return this;
        }
    }
}
