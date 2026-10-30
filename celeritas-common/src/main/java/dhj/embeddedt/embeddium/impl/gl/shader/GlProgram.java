package dhj.embeddedt.embeddium.impl.gl.shader;

import com.mitchej123.lwjgl.GL20;
import com.mitchej123.lwjgl.GL43;
import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;

import dhj.embeddedt.embeddium.impl.gl.GlObject;
import dhj.embeddedt.embeddium.impl.gl.debug.GLDebug;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniform;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformBlock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.IntFunction;

public class GlProgram<T> extends GlObject implements ShaderBindingContext {
    private static final Logger LOGGER = LogManager.getLogger(GlProgram.class);

    private final T shaderInterface;

    protected GlProgram(int program, Function<ShaderBindingContext, T> interfaceFactory) {
        this.setHandle(program);
        this.shaderInterface = interfaceFactory.apply(this);
    }

    public T getInterface() {
        return this.shaderInterface;
    }

    public static Builder builder(String identifier) {
        return new Builder(identifier);
    }

    public void bind() {
        LWJGL.glUseProgram(this.handle());
    }

    public void unbind() {
        LWJGL.glUseProgram(0);
    }

    @Override
    protected void destroyInternal() {
        LWJGL.glDeleteProgram(this.handle());
    }

    @Override
    public <U extends GlUniform<?>> @Nullable U bindUniformIfPresent(String name, IntFunction<U> factory) {
        int index = LWJGL.glGetUniformLocation(this.handle(), name);

        if (index < 0) {
            return null;
        }

        return factory.apply(index);
    }

    @Override
    public @Nullable GlUniformBlock bindUniformBlockIfPresent(String name, int bindingPoint) {
        int index = LWJGL.glGetUniformBlockIndex(this.handle(), name);

        if (index < 0) {
            return null;
        }

        LWJGL.glUniformBlockBinding(this.handle(), index, bindingPoint);

        return new GlUniformBlock(bindingPoint);
    }

    public static class Builder {
        private final String name;
        private final int program;

        public Builder(String name) {
            this.name = name;
            this.program = LWJGL.glCreateProgram();
        }

        public Builder attachShader(GlShader shader) {
            LWJGL.glAttachShader(this.program, shader.handle());

            return this;
        }

        /**
         * Links the attached shaders to this program and returns a user-defined container which wraps the shader
         * program. This container can, for example, provide methods for updating the specific uniforms of that shader
         * set.
         */
        public <U> GlProgram<U> link(Function<ShaderBindingContext, U> factory) {
            LWJGL.glLinkProgram(this.program);

            String log = LWJGL.glGetProgramInfoLog(this.program, 4096);

            if (!log.isEmpty()) {
                LOGGER.warn("Program link log for " + this.name + ": " + log);
            }

            int result = LWJGL.glGetProgrami(this.program, GL20.GL_LINK_STATUS);

            if (result != GL20.GL_TRUE) {
                throw new RuntimeException("Shader program linking failed, see log for details");
            }

            GLDebug.nameObject(GL43.GL_PROGRAM, this.program, this.name);

            return new GlProgram<>(this.program, factory);
        }

        public Builder bindAttribute(String name, int index) {
            LWJGL.glBindAttribLocation(this.program, index, name);

            return this;
        }

        public Builder bindFragmentData(String name, int index) {
            LWJGL.glBindFragDataLocation(this.program, index, name);

            return this;
        }
    }
}

