package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11C;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ErrorCheckIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void disabledChecksSuppressRedirectedErrorsWithoutClearingDriverError() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.applyInitConfig(GLSMInitConfig.builder().noErrorChecks(true).build());

        GL11C.glEnable(-1);
        assertEquals(GL11C.GL_NO_ERROR, GLSMQueries.glGetError());
        assertDoesNotThrow(GLStateManager::checkGLError);
        assertNotEquals(GL11C.GL_NO_ERROR, GL11C.glGetError());
    }
}
