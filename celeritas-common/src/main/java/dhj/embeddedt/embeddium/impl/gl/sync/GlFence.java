package dhj.embeddedt.embeddium.impl.gl.sync;

import com.mitchej123.lwjgl.GL32;
import com.mitchej123.lwjgl.MemoryStack;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;


import java.nio.IntBuffer;

public class GlFence {
    private final long id;
    private boolean disposed;

    public GlFence(long id) {
        this.id = id;
    }

    public boolean isCompleted() {
        this.checkDisposed();

        int result;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            result = LWJGL.glGetSynci(this.id, GL32.GL_SYNC_STATUS, count);

            if (count.get(0) != 1) {
                throw new RuntimeException("glGetSync returned more than one value");
            }
        }

        return result == GL32.GL_SIGNALED;
    }

    public void delete() {
        LWJGL.glDeleteSync(this.id);
        this.disposed = true;
    }

    private void checkDisposed() {
        if (this.disposed) {
            throw new IllegalStateException("Fence object has been disposed");
        }
    }
}

