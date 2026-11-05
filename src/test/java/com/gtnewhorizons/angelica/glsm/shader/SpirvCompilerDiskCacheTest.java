package com.gtnewhorizons.angelica.glsm.shader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpirvCompilerDiskCacheTest {

    private static final String SRC = "#version 460 core\nlayout(location = 0) in vec4 p;\nvoid main() { gl_Position = p; }\n";

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        ShaderDiskCache.resetForTesting();
        SpirvCompiler.clearCache();
    }

    @Test
    void diskEntryServedAfterMemoryClear() {
        final SpirvCompiler.Options opts = SpirvCompiler.Options.vulkanForced460Core();
        ShaderDiskCache.configure(dir, "t");
        final SpirvCompiler.Result first = SpirvCompiler.compile(SRC, Shaderc.shaderc_vertex_shader, "disk-cache-test", opts);
        assertNotNull(first.spirv(), first.error());
        MemoryUtil.memFree(first.spirv());

        SpirvCompiler.clearCache();
        ShaderDiskCache.put(SpirvCompiler.diskKey(SRC, Shaderc.shaderc_vertex_shader, opts), new byte[] {1, 2, 3, 4});
        final SpirvCompiler.Result second = SpirvCompiler.compile(SRC, Shaderc.shaderc_vertex_shader, "disk-cache-test", opts);
        final ByteBuffer spirv = second.spirv();
        assertNotNull(spirv);
        try {
            assertArrayEquals(new byte[] {1, 2, 3, 4}, ShaderCacheIO.toHeap(spirv));
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    @Test
    void diskOptOutSkipsSpirvLayer() {
        ShaderDiskCache.configure(dir, "t");
        final SpirvCompiler.Result r = SpirvCompiler.compile(SRC, Shaderc.shaderc_vertex_shader, "disk-opt-out", SpirvCompiler.Options.vulkanForced460Core(), false);
        assertNotNull(r.spirv(), r.error());
        MemoryUtil.memFree(r.spirv());
        assertFalse(Files.exists(dir.resolve("spirv")));
    }
}
