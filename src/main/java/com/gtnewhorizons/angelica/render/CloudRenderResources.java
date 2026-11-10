package com.gtnewhorizons.angelica.render;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.gl.shader.GlShader;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderConstants;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderType;
import dhj.embeddedt.embeddium.impl.render.shader.ShaderLoader;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

/**
 * Programs and cloud textures the renderer draws with, kept across frames and released on world unload.
 *
 * <p>The four programs are the cross product of the two geometry styles (plates or instanced faces) and
 * the textured or {@code UNTEXTURED} fragment path, where the untextured path is chosen for a fully
 * opaque white cloud mask. Textures are wrapped per location so the shape extraction and the mipmap
 * setup happen once per texture rather than per layer.
 */
final class CloudRenderResources {

    /** Cloud texture wrappers by resource location, so the decoded shape is not rebuilt every frame. */
    private final Map<ResourceLocation, Texture> textures = new HashMap<>();
    /** Linked programs indexed by {@code (faces ? 2 : 0) | (untextured ? 1 : 0)}. */
    @SuppressWarnings("unchecked")
    private final GlProgram<CloudUniforms>[] programs = (GlProgram<CloudUniforms>[]) new GlProgram[4];

    /** Returns the wrapper of a cloud texture, decoding its shape on the first bind. */
    Texture texture(ResourceLocation location) {
        return textures.computeIfAbsent(location, Texture::new);
    }

    /** Returns the program of the requested variant, linking it on first use. */
    GlProgram<CloudUniforms> program(boolean faces, boolean untextured) {
        final int key = (faces ? 2 : 0) | (untextured ? 1 : 0);
        GlProgram<CloudUniforms> program = programs[key];
        if (program == null) {
            program = buildProgram(faces, untextured);
            programs[key] = program;
        }
        return program;
    }

    /**
     * Releases every linked program and drops the texture wrappers. Called on world unload and safe to
     * repeat: the program array is cleared after the deletes, and the wrappers own no GPU name of their
     * own because the texture atlas belongs to the resource manager.
     */
    void clear() {
        for (GlProgram<CloudUniforms> program : programs) {
            if (program != null) program.delete();
        }
        Arrays.fill(programs, null);
        textures.clear();
    }

    private static GlProgram<CloudUniforms> buildProgram(boolean faces, boolean untextured) {
        final ShaderConstants.Builder builder = ShaderConstants.builder();
        if (untextured) builder.add("UNTEXTURED");
        final ShaderConstants constants = builder.build();
        final String vertName = faces ? "angelica:cloud_faces.vert" : "angelica:cloud.vert";
        final String fragName = faces ? "angelica:cloud_faces.frag" : "angelica:cloud.frag";
        final GlShader vert = ShaderLoader.loadShader(ShaderType.VERTEX, vertName, constants);
        final GlShader frag = ShaderLoader.loadShader(ShaderType.FRAGMENT, fragName, constants);
        final GlProgram<CloudUniforms> built;
        try {
            built = GlProgram.builder("angelica:cloud")
                .attachShader(vert)
                .attachShader(frag)
                .link(CloudUniforms::new);
        } finally {
            vert.delete();
            frag.delete();
        }

        final int previous = GLSMPrograms.getActiveProgram();
        GLSMPrograms.glUseProgram(built.handle());
        try {
            if (!untextured) built.getInterface().textureUnit.setInt(0);
        } finally {
            GLSMPrograms.glUseProgram(previous);
        }
        return built;
    }

    /**
     * One cloud texture: binds it through the tracked texture state, forces the wrapping and mipmap
     * settings the cloud field needs, and reads the texels back once to build the {@link CloudShape}.
     */
    static final class Texture {

        /** Resource location this wrapper was created for. */
        final ResourceLocation location;
        /** Decoded cloud cells and rectangles, {@code null} until the first bind. */
        CloudShape shape;
        /** Incremented on every shape extraction so a cached mesh knows when to rebuild. */
        int generation;
        /** Width of the bound texture level in texels, used for the cell coordinate scale. */
        int width;

        private final Minecraft mc = Minecraft.getMinecraft();
        private int mipmappedTexId = -1;
        private int cloudTexId = -1;
        private ByteBuffer texelStagingBuffer;

        private Texture(ResourceLocation location) {
            this.location = location;
        }

        /**
         * Binds the texture and refreshes the shape when the bound texture changed. Returns early while
         * the resource manager is still loading, which happens during the first frames after a resource
         * reload; the renderer then draws nothing for this layer instead of using a stale shape.
         */
        void bind() {
            if (mc.renderEngine == null) return;
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            mc.renderEngine.bindTexture(location);
            final int boundTexId = GLSMQueries.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (boundTexId != mipmappedTexId) {
                GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 4);
                GLSMTextures.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_LINEAR);
                GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
                mipmappedTexId = boundTexId;
            }
            if (boundTexId != cloudTexId || shape == null) {
                extractCloudShape();
                cloudTexId = boundTexId;
            }
        }

        /**
         * Reads the bound level zero back into the staging buffer and decodes it. The buffer grows with the
         * texture and is reused, so a texture reload does not allocate per frame.
         */
        private void extractCloudShape() {
            final int texWidth = GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            final int texHeight = GLSMTextures.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (texWidth <= 0 || texHeight <= 0) return;
            final int neededBytes = texWidth * texHeight * 4;
            if (texelStagingBuffer == null || texelStagingBuffer.capacity() < neededBytes) {
                texelStagingBuffer = BufferUtils.createByteBuffer(neededBytes);
            } else {
                texelStagingBuffer.clear();
            }
            GLSMTextures.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                texelStagingBuffer);
            width = texWidth;
            shape = new CloudShape(texWidth, texHeight, texelStagingBuffer);
            generation++;
        }
    }
}
