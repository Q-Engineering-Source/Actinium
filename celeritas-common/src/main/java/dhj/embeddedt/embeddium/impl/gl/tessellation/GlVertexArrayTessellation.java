package dhj.embeddedt.embeddium.impl.gl.tessellation;

import com.gtnewhorizons.angelica.glsm.debug.TerrainVertexArrayDiagnostics;
import dhj.embeddedt.embeddium.impl.gl.array.GlVertexArray;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlBufferTarget;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;

public class GlVertexArrayTessellation extends GlAbstractTessellation {
    private final GlVertexArray array;

    public GlVertexArrayTessellation(GlVertexArray array, TessellationBinding[] bindings) {
        super(bindings);

        this.array = array;
    }

    public void init(CommandList commandList) {
        this.bind(commandList);
        this.bindAttributes(commandList);
        this.unbind(commandList);
    }

    /** Registers only terrain-created VAOs, using the buffers already retained by this tessellation. */
    public void registerTerrainDiagnostics() {
        if (!TerrainVertexArrayDiagnostics.isTerrainVaoDiagnosticsEnabled()) {
            return;
        }
        int vertexBufferId = 0;
        int elementBufferId = 0;
        for (TessellationBinding binding : this.bindings) {
            if (binding.target() == GlBufferTarget.ARRAY_BUFFER) {
                vertexBufferId = binding.buffer().handle();
            } else if (binding.target() == GlBufferTarget.ELEMENT_BUFFER) {
                elementBufferId = binding.buffer().handle();
            }
        }
        this.array.registerTerrainDiagnostics(vertexBufferId, elementBufferId);
    }

    @Override
    public void delete(CommandList commandList) {
        commandList.deleteVertexArray(this.array);
    }

    @Override
    public void bind(CommandList commandList) {
        commandList.bindVertexArray(this.array);
    }

    @Override
    public void unbind(CommandList commandList) {
        commandList.unbindVertexArray();
    }
}
