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

/**
 * A destroyed program wrapper must give up its push notifications even when another program is bound, because
 * a notification writes to a uniform location of whatever program is bound: a leftover registration would
 * write the destroyed program's values into the next one.
 *
 * <p>Once a program is destroyed its GL name may be kept by {@link RetainedPrograms} for the next pipeline, so
 * the stale registration would not even be paired with a deleted name - it would write into a program the
 * user can see. Notifiers are shared between programs, so the removal has to be per registration: clearing the
 * notifier would silence the programs that are still alive.</p>
 *
 * <p>{@link Program} itself cannot be built without a GL context ({@link ProgramSamplers} and
 * {@link ProgramImages} read the driver limits), so the contract is exercised on {@link ProgramUniforms},
 * which owns the registrations {@link Program#destroyInternal()} removes first.</p>
 */
class ProgramUniformRetentionTest {
    private final MultiListenerNotifier notifier = new MultiListenerNotifier();

    @AfterEach
    void clearActiveProgram() {
        ProgramUniforms.clearActiveUniforms();
        notifier.setListener(null);
    }

    @Test
    void destroyingTheBoundProgramStopsItsNotifications() {
        Counter counter = new Counter();
        ProgramUniforms program = program(counter);
        program.update(0, 0);
        notifier.run();
        assertEquals(2, counter.reads.get(), "one read while updating, one while notified");

        program.removeListeners();
        notifier.run();

        assertEquals(2, counter.reads.get(), "a destroyed program must not keep updating its uniforms");
    }

    @Test
    void destroyingAnInactiveProgramDoesNotSilenceTheBoundOne() {
        Counter first = new Counter();
        Counter second = new Counter();
        ProgramUniforms firstProgram = program(first);
        ProgramUniforms secondProgram = program(second);

        firstProgram.update(0, 0);
        secondProgram.update(0, 0);
        firstProgram.removeListeners();
        notifier.run();

        assertEquals(1, first.reads.get(), "the inactive program has nothing left to notify");
        assertEquals(2, second.reads.get(), "the bound program keeps its notification");
    }

    @Test
    void aDestroyedProgramCannotWriteIntoTheProgramBoundAfterIt() {
        Counter destroyed = new Counter();
        Counter bound = new Counter();
        ProgramUniforms destroyedProgram = program(destroyed);
        ProgramUniforms boundProgram = program(bound);

        destroyedProgram.update(0, 0);
        destroyedProgram.removeListeners();
        boundProgram.update(0, 0);
        int readsAtDestroy = destroyed.reads.get();

        notifier.run();

        assertEquals(readsAtDestroy, destroyed.reads.get(),
            "the destroyed program's listener must not run against the newly bound program");
        assertEquals(2, bound.reads.get());
    }

    @Test
    void removingListenersTwiceOnlyTakesBackTheSameRegistrations() {
        Counter first = new Counter();
        Counter second = new Counter();
        ProgramUniforms firstProgram = program(first);
        ProgramUniforms secondProgram = program(second);

        firstProgram.update(0, 0);
        secondProgram.update(0, 0);
        firstProgram.update(1, 1);
        secondProgram.removeListeners();
        secondProgram.removeListeners();

        notifier.run();

        assertEquals(3, first.reads.get(), "a repeated removal of the inactive program leaves the bound one alone");
        assertEquals(1, second.reads.get());
    }

    @Test
    void unbindingOnlyClearsTheProgramThatWasBound() {
        Counter first = new Counter();
        Counter second = new Counter();
        ProgramUniforms firstProgram = program(first);
        ProgramUniforms secondProgram = program(second);

        firstProgram.update(0, 0);
        firstProgram.removeListeners();
        secondProgram.update(0, 0);

        ProgramUniforms.clearActiveUniforms();
        notifier.run();

        assertEquals(1, first.reads.get());
        assertEquals(1, second.reads.get());
    }

    private ProgramUniforms program(Counter counter) {
        CollectingHolder holder = new CollectingHolder();
        FloatSupplier value = () -> {
            counter.reads.incrementAndGet();
            return 0.0f;
        };
        holder.uniform1f("dynamic", value, notifier);

        return new ProgramUniforms(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
            ImmutableList.copyOf(holder.uniforms));
    }

    private static final class Counter {
        private final AtomicInteger reads = new AtomicInteger();
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
