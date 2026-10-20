package net.coderbot.iris.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A GL name can be reachable through more than one destruction path: a shared program is known to every pass
 * that uses it, and a program handed to the retained-program pool is still named after its wrapper is gone.
 * {@link GlResource#destroy()} has to release the name exactly once.
 */
class GlResourceDestroyTest {

    @Test
    void destroyingAResourceReleasesItOnce() {
        CountingResource resource = new CountingResource(7);

        resource.destroy();
        assertEquals(1, resource.releases);

        resource.destroy();
        assertEquals(1, resource.releases, "a second destroy must not release the name again");
    }

    @Test
    void aDestroyedResourceCannotHandOutItsName() {
        CountingResource resource = new CountingResource(7);
        resource.destroy();

        assertThrows(IllegalStateException.class, resource::id);
    }

    /** Minimal resource whose only behaviour is counting how often it is told to release its name. */
    private static final class CountingResource extends GlResource {
        private int releases;

        private CountingResource(int id) {
            super(id);
        }

        private int id() {
            return getGlId();
        }

        @Override
        protected void destroyInternal() {
            releases++;
        }
    }
}
