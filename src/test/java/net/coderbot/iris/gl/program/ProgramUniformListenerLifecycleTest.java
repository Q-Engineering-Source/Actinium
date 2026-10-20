package net.coderbot.iris.gl.program;

import com.google.common.collect.ImmutableList;
import net.coderbot.iris.gl.state.MultiListenerNotifier;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.coderbot.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.coderbot.iris.gl.uniform.FloatSupplier;
import net.coderbot.iris.gl.uniform.LocationalUniformHolder;
import net.coderbot.iris.gl.uniform.Uniform;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.gl.uniform.UniformType;
import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgramUniformListenerLifecycleTest {
    @AfterEach
    void clearActiveProgram() {
        ProgramUniforms.clearActiveUniforms();
    }

    @Test
    void sharedNotifierUpdatesAllUniformsWithoutGrowingAcrossRepeatedUses() {
        CountingNotifier notifier = new CountingNotifier();
        AtomicInteger firstReads = new AtomicInteger();
        AtomicInteger secondReads = new AtomicInteger();
        ProgramUniforms program = program(notifier, firstReads, secondReads);

        for (int i = 0; i < 100; i++) {
            program.update(i, i);
        }
        assertEquals(0, notifier.clears, "the active program retains its listener registrations");
        assertEquals(100, firstReads.get());
        assertEquals(100, secondReads.get());

        notifier.run();
        assertEquals(101, firstReads.get());
        assertEquals(101, secondReads.get());

        ProgramUniforms.clearActiveUniforms();
        notifier.run();
        assertEquals(101, firstReads.get());
        assertEquals(101, secondReads.get());
    }

    @Test
    void switchingProgramsRemovesOldListenersAndCanReactivateTheOriginal() {
        CountingNotifier notifier = new CountingNotifier();
        AtomicInteger firstReads = new AtomicInteger();
        AtomicInteger secondReads = new AtomicInteger();
        ProgramUniforms first = program(notifier, firstReads);
        ProgramUniforms second = program(notifier, secondReads);

        first.update(0, 0);
        notifier.run();
        assertEquals(2, firstReads.get());

        second.update(0, 0);
        notifier.run();
        assertEquals(2, firstReads.get());
        assertEquals(2, secondReads.get());

        // Disposing an inactive program must not clear the active program's shared notifier.
        first.removeListeners();
        notifier.run();
        assertEquals(3, secondReads.get());

        first.update(1, 1);
        notifier.run();
        assertEquals(4, firstReads.get());
        assertEquals(3, secondReads.get());

        first.removeListeners();
        notifier.run();
        assertEquals(4, firstReads.get());
    }

    private static ProgramUniforms program(ValueUpdateNotifier notifier, AtomicInteger... reads) {
        CollectingHolder holder = new CollectingHolder();
        for (int i = 0; i < reads.length; i++) {
            AtomicInteger counter = reads[i];
            FloatSupplier value = () -> {
                counter.incrementAndGet();
                return 0.0f;
            };
            holder.uniform1f("uniform" + i, value, notifier);
        }
        return new ProgramUniforms(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
            ImmutableList.copyOf(holder.uniforms));
    }

    private static final class CountingNotifier implements ValueUpdateNotifier {
        private final MultiListenerNotifier delegate = new MultiListenerNotifier();
        private int clears;

        @Override
        public void setListener(Runnable listener) {
            if (listener == null) {
                clears++;
            }
            delegate.setListener(listener);
        }

        @Override
        public void removeListener(Runnable listener) {
            delegate.removeListener(listener);
        }

        private void run() {
            delegate.run();
        }
    }

    /** Uses the real dynamic uniform constructors without allocating an OpenGL program. */
    private static final class CollectingHolder implements DynamicLocationalUniformHolder {
        private final List<Uniform> uniforms = new ArrayList<>();

        @Override
        public DynamicLocationalUniformHolder addDynamicUniform(Uniform uniform, ValueUpdateNotifier notifier) {
            uniforms.add(uniform);
            return this;
        }

        @Override
        public LocationalUniformHolder addUniform(UniformUpdateFrequency frequency, Uniform uniform) {
            uniforms.add(uniform);
            return this;
        }

        @Override
        public OptionalInt location(String name, UniformType type) {
            return OptionalInt.of(uniforms.size());
        }

        @Override
        public UniformHolder externallyManagedUniform(String name, UniformType type) {
            return this;
        }
    }
}
