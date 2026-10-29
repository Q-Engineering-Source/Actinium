package dhj.embeddedt.embeddium.impl.gl.array;

import com.gtnewhorizons.angelica.glsm.debug.TerrainVertexArrayDiagnostics;
import dhj.embeddedt.embeddium.impl.gl.GlObject;
import com.mitchej123.lwjgl.LWJGLServiceProvider;

/**
 * Provides Vertex Array functionality on supported platforms.
 */
public class GlVertexArray extends GlObject {
    public static final int NULL_ARRAY_ID = 0;
    private TerrainVertexArrayDiagnostics.TraceToken terrainTraceToken;

    public GlVertexArray() {
        this.setHandle(LWJGLServiceProvider.LWJGL.glGenVertexArrays());
    }

    /** Marks this VAO for the terrain-only context ownership probe. */
    public void registerTerrainDiagnostics(int vertexBufferId, int elementBufferId) {
        this.terrainTraceToken = TerrainVertexArrayDiagnostics.registerTerrainVertexArray(
                this.handle(), vertexBufferId, elementBufferId);
    }

    @Override
    protected void destroyInternal() {
        int vertexArrayId = this.handle();
        TerrainVertexArrayDiagnostics.DeleteScope scope =
                TerrainVertexArrayDiagnostics.beginServiceDelete(this.terrainTraceToken);
        try {
            LWJGLServiceProvider.LWJGL.glDeleteVertexArrays(vertexArrayId);
            TerrainVertexArrayDiagnostics.finishServiceDelete(scope, null);
        } catch (RuntimeException | Error failure) {
            TerrainVertexArrayDiagnostics.finishServiceDelete(scope, failure);
            throw failure;
        } finally {
            TerrainVertexArrayDiagnostics.closeServiceDelete(scope);
        }
    }
}

