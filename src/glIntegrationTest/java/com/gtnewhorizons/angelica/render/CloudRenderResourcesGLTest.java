package com.gtnewhorizons.angelica.render;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Compiles and links the four cloud programs against a real context and checks which uniforms reach each
 * variant, so a shader that stops declaring a uniform is caught here instead of at the first frame.
 */
class CloudRenderResourcesGLTest extends GLStateManagerIntegrationFixture {

    private CloudRenderResources resources;

    @BeforeAll
    static void initializeGlsm() {
        installDefaultGlsmConfig();
    }

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        resources = new CloudRenderResources();
    }

    @AfterEach
    void tearDown() {
        resources.clear();
        GLSMVertexInput.glBindVertexArray(0);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Drain anything the driver queued so the next test starts clean.
        }
    }

    @Test
    void texturedPlaneProgramBindsTheFrameUniforms() {
        final CloudUniforms uniforms = resources.program(false, false).getInterface();

        assertNotNull(uniforms.mvp, "u_MVPMatrix");
        assertNotNull(uniforms.modelView, "u_MVMatrix");
        assertNotNull(uniforms.colorMult, "u_ColorMult");
        assertNotNull(uniforms.fogParams, "u_FogParams");
        assertNotNull(uniforms.fogColor, "u_FogColor");
        assertNotNull(uniforms.fogEnabled, "u_FogEnabled");
        assertNotNull(uniforms.textureUnit, "u_Tex");
        assertNull(uniforms.cellHeight, "the plane program has no u_CellHeight");
        assertNull(uniforms.scroll, "the plane program has no u_Scroll");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void untexturedPlaneProgramDropsTheSampler() {
        final CloudUniforms uniforms = resources.program(false, true).getInterface();

        assertNull(uniforms.textureUnit, "UNTEXTURED has no sampler");
        assertNotNull(uniforms.colorMult, "the colour still comes from the uniform");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void facesProgramBindsCellHeightAndScroll() {
        final CloudUniforms uniforms = resources.program(true, false).getInterface();

        assertNotNull(uniforms.cellHeight, "u_CellHeight");
        assertNotNull(uniforms.scroll, "u_Scroll");
        assertNotNull(uniforms.textureUnit, "u_Tex");
        assertNotNull(uniforms.mvp, "u_MVPMatrix");
        assertNotNull(uniforms.modelView, "u_MVMatrix");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void untexturedFacesProgramKeepsCellHeightWithoutScrollOrSampler() {
        final CloudUniforms uniforms = resources.program(true, true).getInterface();

        assertNotNull(uniforms.cellHeight, "the instanced geometry still needs the cell height");
        assertNull(uniforms.scroll, "the UNTEXTURED face program declares no scroll");
        assertNull(uniforms.textureUnit, "UNTEXTURED has no sampler");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void programsAreCachedPerVariant() {
        assertSame(resources.program(false, false), resources.program(false, false));
        assertSame(resources.program(true, true), resources.program(true, true));
        assertNotSame(resources.program(false, false), resources.program(false, true));
        assertNotSame(resources.program(false, false), resources.program(true, false));
        assertNotSame(resources.program(true, false), resources.program(true, true));
    }

    @Test
    void clearReleasesTheProgramsAndCanBeRepeated() {
        final Object first = resources.program(true, false);
        assertSame(first, resources.program(true, false));

        resources.clear();
        resources.clear();

        assertNotSame(first, resources.program(true, false), "clear() must drop the linked program");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void texturesAreCachedPerLocationAndSurviveClearAsAnEmptyCache() {
        final ResourceLocation location = new ResourceLocation("minecraft", "textures/environment/clouds.png");
        final CloudRenderResources.Texture first = resources.texture(location);

        assertSame(first, resources.texture(location), "the texture wrapper is cached per location");

        resources.clear();

        assertNotSame(first, resources.texture(location), "clear() drops the wrapper cache without deleting the atlas texture");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }
}
