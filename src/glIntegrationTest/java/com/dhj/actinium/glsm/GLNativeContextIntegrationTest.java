package com.dhj.actinium.glsm;

import com.dhj.actinium.render.VanillaBufferBuilderRenderer;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizons.angelica.glsm.DisplayListManager;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.StateSet;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistry;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.glsm.recording.CommandBuffer;
import com.gtnewhorizons.angelica.glsm.recording.CommandRecorder;
import com.gtnewhorizons.angelica.glsm.recording.CompiledDisplayList;
import com.gtnewhorizons.angelica.glsm.recording.ContextOwnedVertexArray;
import com.gtnewhorizons.angelica.glsm.recording.GLCommand;
import com.gtnewhorizons.angelica.glsm.recording.commands.AttribLayoutKey;
import com.gtnewhorizons.angelica.glsm.recording.commands.BatchedIndexedDrawCmd;
import com.gtnewhorizons.angelica.glsm.recording.commands.IndexedDrawBatch;
import com.gtnewhorizons.angelica.glsm.recording.commands.StateSetScopeCmd;
import com.gtnewhorizons.angelica.glsm.states.BufferGenerationTracker;
import com.gtnewhorizons.angelica.glsm.states.VertexAttribState;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in native GL validation for context-owned state and display-list resources.
 *
 * <p>This test binds real hidden GLFW handles through the production registry API. It does not bootstrap
 * the game's Mixin environment; its helpers call the same display-list bind, unbind, and owner-abort hooks
 * used by the ContextGL mixin, but do not verify that bytecode injection itself runs.</p>
 */
class GLNativeContextIntegrationTest {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final GLContextRegistry REGISTRY = GLContextRegistryProvider.get();

    private static GLFWErrorCallback errorCallback;
    private static boolean glfwInitialized;
    private static boolean openGLInitialized;
    private static TestContext contextA;
    private static TestContext contextB;
    private static TestContext contextC;
    private static TestContext contextD;
    private static HookCapture activeHookCapture;

    @BeforeAll
    static void createHiddenNativeContexts() {
        errorCallback = GLFWErrorCallback.createPrint(System.err).set();
        glfwInitialized = GLFW.glfwInit();
        assertTrue(glfwInitialized, "GLFW must initialize; this integration task requires a native display driver");

        hintCompatibilityContext();
        contextA = createContext(0L);
        activate(contextA);
        GLStateManager.initialize(GLSMInitConfig.builder().displaySize(WIDTH, HEIGHT).build());
        contextA.framebuffer = createFramebuffer();

        hintCompatibilityContext();
        contextB = createContext(contextA.window);
        contextB.identity = REGISTRY.registerSharedContext(contextB.token, contextB.window, contextA.token);
        activate(contextB);
        assertEquals(contextB.identity, REGISTRY.currentBinding());
        contextB.framebuffer = createFramebuffer();

        hintCompatibilityContext();
        contextC = createContext(0L);
        activate(contextC);

        hintCompatibilityContext();
        contextD = createContext(contextA.window);
        contextD.identity = REGISTRY.registerSharedContext(contextD.token, contextD.window, contextA.token);
        activate(contextD);
        assertEquals(contextD.identity, REGISTRY.currentBinding());
        contextD.framebuffer = createFramebuffer();

        assertEquals(contextA.identity.shareGroupId(), contextB.identity.shareGroupId(),
            "the GLFW child context shares A's object namespace");
        assertNotEquals(contextA.identity.shareGroupId(), contextC.identity.shareGroupId(),
            "the third context is independent");
    }

    @AfterAll
    static void destroyNativeContexts() {
        try {
            if (glfwInitialized) {
                try {
                    releaseCurrentContext();
                    retireAndDestroy(contextA);
                    retireAndDestroy(contextB);
                    retireAndDestroy(contextC);
                    retireAndDestroy(contextD);
                } finally {
                    GLFW.glfwTerminate();
                }
            }
        } finally {
            if (errorCallback != null) {
                errorCallback.free();
            }
        }
    }

    @Test
    void nativeContextsRestoreAttribStacksIsolateNamesAndReplayCompiledIndexedDraws() {
        verifyAttribStackRestoration();
        verifySameNumberBuffersInIndependentGroups();
        verifyClientAttribRestoration();
        verifyContextOwnedVaoLayoutsAndReuse();
        verifySharedAndIndependentDisplayListNamespaces();
        verifyPerContextCompilationSessionsSurviveRelease();
        verifyCompiledListReplayAndDeferredVaoDelete();
        retireSiblingContextWhileShareGroupRemainsLive();
        verifyCowRestoreHookNotifications();
        retireOwnerContextWhileItsShareGroupRemainsLive();
        verifyRetiringNestedParentLeavesChildCompilationActive();
        verifyDefaultVertexArrayMigrationReplaysClientArrays();
    }

    private static void verifyAttribStackRestoration() {
        activate(contextA);
        setAttribState(false, true, true, GL11.GL_LESS, true,
            true, false, true, false, 3, 5, 17, 19);
        assertAttribState(false, true, true, GL11.GL_LESS, true,
            true, false, true, false, 3, 5, 17, 19);

        final int mask = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
            | GL11.GL_SCISSOR_BIT;
        GLStateManager.glPushAttrib(mask);
        setAttribState(true, false, false, GL11.GL_GREATER, false,
            false, true, false, true, 23, 29, 31, 37);
        assertAttribState(true, false, false, GL11.GL_GREATER, false,
            false, true, false, true, 23, 29, 31, 37);

        GLStateManager.glPushAttrib(mask);
        setAttribState(false, true, true, GL11.GL_ALWAYS, true,
            false, false, true, true, 41, 43, 47, 53);
        assertAttribState(false, true, true, GL11.GL_ALWAYS, true,
            false, false, true, true, 41, 43, 47, 53);

        GLStateManager.glPopAttrib();
        assertAttribState(true, false, false, GL11.GL_GREATER, false,
            false, true, false, true, 23, 29, 31, 37);
        GLStateManager.glPopAttrib();
        assertAttribState(false, true, true, GL11.GL_LESS, true,
            true, false, true, false, 3, 5, 17, 19);
        assertNoGlError("nested glPushAttrib/glPopAttrib");
    }

    private static void verifyClientAttribRestoration() {
        activate(contextA);
        final int originalBuffer = GLStateManager.glGenBuffers();
        final int replacementBuffer = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, originalBuffer);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 64L, GL15.GL_STATIC_DRAW);
        GLStateManager.glVertexPointer(3, GL11.GL_FLOAT, 12, 0L);
        GLStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);

        GLStateManager.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, replacementBuffer);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 64L, GL15.GL_STATIC_DRAW);
        GLStateManager.glVertexPointer(2, GL11.GL_FLOAT, 8, 4L);
        GLStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GLStateManager.glPopClientAttrib();

        final int positionAttribute = Usage.POSITION.getAttributeLocation();
        assertTrue(GL20.glGetVertexAttribi(positionAttribute, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0,
            "client vertex array enable must be restored to the generic position attribute");
        assertEquals(3, GL20.glGetVertexAttribi(positionAttribute, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
        assertEquals(GL11.GL_FLOAT, GL20.glGetVertexAttribi(positionAttribute, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE));
        assertEquals(12, GL20.glGetVertexAttribi(positionAttribute, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE));
        assertEquals(originalBuffer,
            GL20.glGetVertexAttribi(positionAttribute, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING),
            "the vertex pointer must keep the buffer captured before the client attrib push");
        assertNoGlError("glPushClientAttrib/glPopClientAttrib");

        GLStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glDeleteBuffers(originalBuffer);
        GLStateManager.glDeleteBuffers(replacementBuffer);
    }

    private static void verifyDefaultVertexArrayMigrationReplaysClientArrays() {
        activate(contextC);
        GLStateManager.recreateDefaultVertexArray();
        contextC.framebuffer = createFramebuffer();

        final VertexAttribState arrays = GLStateManager.getVertexAttribState();
        final int previousDefaultVao = GLStateManager.getDefaultVAO();
        final int positionAttribute = Usage.POSITION.getAttributeLocation();
        final ByteBuffer vertices = BufferUtils.createByteBuffer(9 * Float.BYTES);
        vertices.putFloat(-0.75F).putFloat(-0.75F).putFloat(0.0F);
        vertices.putFloat(0.75F).putFloat(-0.75F).putFloat(0.0F);
        vertices.putFloat(0.0F).putFloat(0.75F).putFloat(0.0F);
        vertices.flip();

        GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE0);
        GLStateManager.glVertexPointer(3, GL11.GL_FLOAT, 0, vertices);
        GLStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        final VertexAttribState.Attrib earlyPointer = arrays.getConventionalAttrib(positionAttribute);
        assertTrue(earlyPointer.enabled);
        assertEquals(0, earlyPointer.stride);
        assertEquals(0, earlyPointer.vboId);
        assertEquals(9 * Float.BYTES, earlyPointer.clientPointer.capacity());

        GLStateManager.recreateDefaultVertexArray();
        final int replacementVao = GLStateManager.getDefaultVAO();
        assertNotEquals(previousDefaultVao, replacementVao,
            "default VAO recreation must allocate a new native name");

        final VertexAttribState.Attrib migratedPointer = arrays.getConventionalAttrib(positionAttribute);
        assertTrue(migratedPointer.enabled,
            "the replacement default VAO retains the enabled legacy position array");
        assertEquals(0, migratedPointer.vboId,
            "client memory remains the logical source until the draw prepares its core-profile upload");
        assertEquals(9 * Float.BYTES, migratedPointer.clientPointer.capacity());

        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, contextC.framebuffer.framebuffer);
        GLStateManager.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GLStateManager.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GLStateManager.glViewport(0, 0, WIDTH, HEIGHT);
        GLStateManager.glDisable(GL11.GL_BLEND);
        GLStateManager.glDisable(GL11.GL_CULL_FACE);
        GLStateManager.glDisable(GL11.GL_DEPTH_TEST);
        GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
        GLStateManager.glDisable(GL11.GL_LIGHTING);
        GLStateManager.glDisable(GL11.GL_TEXTURE_2D);
        GLStateManager.glColorMask(true, true, true, true);
        GLStateManager.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GLStateManager.glColor4f(1.0F, 0.0F, 0.0F, 1.0F);
        GLStateManager.glUseProgram(0);
        GLStateManager.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GLStateManager.glFinish();

        final int pointerBuffer = GL20.glGetVertexAttribi(
            positionAttribute, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
        assertTrue(pointerBuffer == 0 || GL15.glIsBuffer(pointerBuffer),
            "a core-profile client-array draw may use GLSM's live upload VBO");
        final ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GLStateManager.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals(255, pixel.get(0) & 255, "the migrated CPU pointer must still draw red at the center");
        assertEquals(0, pixel.get(1) & 255);
        assertEquals(0, pixel.get(2) & 255);
        assertEquals(255, pixel.get(3) & 255);

        GLStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glDeleteVertexArrays(previousDefaultVao);
        assertNoGlError("default VAO recreation with a migrated CPU client pointer");
    }

    private static void verifySameNumberBuffersInIndependentGroups() {
        activate(contextA);
        final int sameBufferName = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, sameBufferName);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{1.0F, 2.0F, 3.0F}, GL15.GL_STATIC_DRAW);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        final BufferGenerationTracker trackerA = BufferGenerationTracker.shared(contextA.identity.shareGroupId());
        final long generationA = trackerA.current(sameBufferName);

        activate(contextC);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, sameBufferName);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{7.0F, 8.0F, 9.0F}, GL15.GL_STATIC_DRAW);
        final BufferGenerationTracker trackerC = BufferGenerationTracker.shared(contextC.identity.shareGroupId());
        final long generationC = trackerC.current(sameBufferName);
        assertTrue(GL15.glIsBuffer(sameBufferName),
            "an independent context can own a real buffer with A's same numeric name");
        assertEquals(sameBufferName, GL15.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING));

        GLStateManager.deleteBufferForShareGroup(contextA.identity.shareGroupId(), sameBufferName);
        assertFalse(trackerA.isCurrent(sameBufferName, generationA),
            "deleting A's buffer invalidates its share-group lifetime");
        assertTrue(trackerC.isCurrent(sameBufferName, generationC),
            "deleting a tracked name in A must not invalidate C's same-number lifetime");
        assertTrue(GL15.glIsBuffer(sameBufferName),
            "deleting A's same-number buffer from C must not touch C's native object");
        assertEquals(sameBufferName, GL15.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
            "deleting A's buffer must preserve C's driver binding");
        assertEquals(sameBufferName, GLStateManager.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
            "deleting A's buffer must preserve C's tracked binding");

        activate(contextA);
        assertTrue(GL15.glIsBuffer(sameBufferName), "A's deletion waits until its owner context releases the binding");
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        assertFalse(GL15.glIsBuffer(sameBufferName), "A drains its queued deletion on its own context");

        activate(contextC);
        assertTrue(GL15.glIsBuffer(sameBufferName), "draining A's queue leaves C's same-number buffer intact");
        assertEquals(sameBufferName, GL15.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
            "switching back to C restores its same-number binding");
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glDeleteBuffers(sameBufferName);
        assertFalse(GL15.glIsBuffer(sameBufferName));
        assertNoGlError("same-number buffer isolation across independent share groups");
    }

    private static void verifyContextOwnedVaoLayoutsAndReuse() {
        activate(contextA);
        final int vertexBuffer = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{-1.0F, -1.0F, 0.0F, 1.0F, 1.0F, 0.0F},
            GL15.GL_STATIC_DRAW);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        final ContextOwnedVertexArray owner = new ContextOwnedVertexArray(contextA.identity.shareGroupId());
        final int vaoA = owner.getOrCreate(contextA.identity,
            () -> configureAttributeZero(vertexBuffer, 2));
        final int cachedA = owner.getOrCreate(contextA.identity,
            () -> { throw new AssertionError("A's VAO layout must only be configured once"); });
        assertEquals(vaoA, cachedA);

        activate(contextB);
        final int contextDefaultVao = GLStateManager.glGenVertexArrays();
        GLStateManager.glBindVertexArray(contextDefaultVao);
        final int vaoB = owner.getOrCreate(contextB.identity,
            () -> configureAttributeZero(vertexBuffer, 3));
        final int cachedB = owner.getOrCreate(contextB.identity,
            () -> { throw new AssertionError("B's VAO layout must only be configured once"); });
        assertEquals(vaoB, cachedB);
        assertEquals(vaoA, vaoB,
            "separate contexts may use the same VAO integer for independent layouts");
        assertEquals(3, vertexAttributeSize(vaoB));

        activate(contextA);
        assertEquals(vaoA, owner.getOrCreate(contextA.identity,
            () -> { throw new AssertionError("A must reuse its own VAO after returning from B"); }));
        assertEquals(2, vertexAttributeSize(vaoA),
            "A's layout remains distinct even though its VAO integer matches B's");

        activate(contextB);
        owner.delete();
        GLStateManager.glDeleteVertexArrays(contextDefaultVao);
        activate(contextA);
        assertTrue(GL30.glIsVertexArray(vaoA),
            "B's delete queues A's context-local VAO until A becomes current");
        GLStateManager.glBindVertexArray(0);
        assertFalse(GL30.glIsVertexArray(vaoA), "A drains the deferred VAO deletion");
        assertNoGlError("context-owned VAO layout and A to B to A reuse");

        activate(contextB);
        GLStateManager.deleteBufferForShareGroup(contextA.identity.shareGroupId(), vertexBuffer);
    }

    private static void verifySharedAndIndependentDisplayListNamespaces() {
        activate(contextA);
        final int listIdA = GLStateManager.glGenLists(1);
        assertFalse(GLStateManager.glIsList(listIdA), "glGenLists reserves the name before compilation");
        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);
        final boolean blendBeforeCompile = GLStateManager.glIsEnabled(GL11.GL_BLEND);
        GLStateManager.glNewList(listIdA, GL11.GL_COMPILE);
        GLStateManager.pushState(StateSet.forMask(GL11.GL_ENABLE_BIT));
        GLStateManager.glEnable(GL11.GL_BLEND);
        GLStateManager.popState();
        GLStateManager.glEnable(GL11.GL_LINE_SMOOTH);
        GLStateManager.glEndList();
        assertEquals(blendBeforeCompile, GLStateManager.glIsEnabled(GL11.GL_BLEND),
            "GL_COMPILE records the StateSet scope without executing its blend change");
        assertFalse(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH),
            "GL_COMPILE records its persistent command without executing it");
        final CompiledDisplayList listA = GLStateManager.getDisplayList(listIdA);
        assertNotNull(listA);
        assertEquals(contextA.identity, listA.getCompilationIdentity());
        assertEquals(2, countStateSetScopeCommands(listA), "the list records both StateSet scope boundaries");

        activate(contextB);
        assertTrue(GLStateManager.glIsList(listIdA), "a context in A's share group sees A's compiled list");
        assertSame(listA, GLStateManager.getDisplayList(listIdA),
            "the shared context resolves the same compiled list object");
        final int nextSharedListId = GLStateManager.glGenLists(1);
        assertNotEquals(listIdA, nextSharedListId, "shared contexts allocate from one display-list namespace");
        GLStateManager.glDeleteLists(nextSharedListId, 1);

        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);
        final boolean blendBeforeSharedCall = GLStateManager.glIsEnabled(GL11.GL_BLEND);
        GLStateManager.glCallList(listIdA);
        assertEquals(blendBeforeSharedCall, GLStateManager.glIsEnabled(GL11.GL_BLEND),
            "the recorded StateSet restores blend state after replay in a shared context");
        assertTrue(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH),
            "a shared context executes the compiled list's persistent command");

        activate(contextC);
        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);
        final int listIdC = GLStateManager.glGenLists(1);
        assertEquals(listIdA, listIdC, "an independent share group starts with its own same-number list name");
        assertFalse(GLStateManager.glIsList(listIdC), "C's same-number name is not A's compiled list");
        GLStateManager.glCallList(listIdC);
        assertFalse(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH),
            "an undefined same-number name in C must not execute A's list");
        assertNoGlError("undefined C display-list name while A owns the same integer");
        GLStateManager.glScissor(2, 4, 101, 53);
        final int[] scissorBeforeCompile = currentScissorBox();
        GLStateManager.glNewList(listIdC, GL11.GL_COMPILE);
        GLStateManager.pushState(StateSet.forMask(GL11.GL_SCISSOR_BIT));
        GLStateManager.glScissor(7, 9, 23, 29);
        GLStateManager.popState();
        GLStateManager.glEndList();
        assertArrayEquals(scissorBeforeCompile, currentScissorBox(),
            "GL_COMPILE records C's scissor change without applying it");
        final CompiledDisplayList listC = GLStateManager.getDisplayList(listIdC);
        assertNotNull(listC);
        assertNotSame(listA, listC, "independent namespaces keep distinct lists with the same integer name");
        assertEquals(contextC.identity, listC.getCompilationIdentity());
        assertEquals(2, countStateSetScopeCommands(listC), "C's list records its own StateSet scope");
        assertNotEquals(listA.getCommandOpcodes(), listC.getCommandOpcodes(),
            "same-number lists in separate namespaces retain their different state commands");

        activate(contextA);
        assertSame(listA, GLStateManager.getDisplayList(listIdA),
            "compiling C's same-number list leaves A's lookup unchanged");
        final boolean blendBeforeCall = GLStateManager.glIsEnabled(GL11.GL_BLEND);
        GLStateManager.glCallList(listIdA);
        assertEquals(blendBeforeCall, GLStateManager.glIsEnabled(GL11.GL_BLEND),
            "A's compiled StateSet restores blend state after replay");
        assertTrue(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH),
            "A's namespace still executes its own compiled list after C compilation");
        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);

        activate(contextC);
        assertSame(listC, GLStateManager.getDisplayList(listIdC),
            "C resolves its own same-number list");
        final int[] scissorBeforeCall = currentScissorBox();
        GLStateManager.glCallList(listIdC);
        assertArrayEquals(scissorBeforeCall, currentScissorBox(),
            "C's compiled StateSet restores the scissor box after replay");
        assertFalse(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH),
            "C's independent same-number list does not contain A's persistent command");

        activate(contextA);
        GLStateManager.glDeleteLists(listIdA, 1);
        assertFalse(GLStateManager.glIsList(listIdA));
        activate(contextB);
        assertFalse(GLStateManager.glIsList(listIdA), "deleting in A is visible throughout its share group");
        activate(contextC);
        assertTrue(GLStateManager.glIsList(listIdC), "deleting A's same-number list leaves C's list intact");
        GLStateManager.glDeleteLists(listIdC, 1);
        assertFalse(GLStateManager.glIsList(listIdC));
        assertNoGlError("shared and independent display-list namespaces with recorded StateSet scopes");
    }

    private static int countStateSetScopeCommands(CompiledDisplayList list) {
        int count = 0;
        for (Object command : list.getComplexObjects()) {
            if (command instanceof StateSetScopeCmd) {
                count++;
            }
        }
        return count;
    }

    private static int[] currentScissorBox() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer scissorBox = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            return new int[]{scissorBox.get(0), scissorBox.get(1), scissorBox.get(2), scissorBox.get(3)};
        }
    }

    private static void verifyPerContextCompilationSessionsSurviveRelease() {
        activate(contextA);
        final int listIdA = GLStateManager.glGenLists(1);
        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);
        GLStateManager.glNewList(listIdA, GL11.GL_COMPILE);
        GLStateManager.pushState(StateSet.forMask(GL11.GL_ENABLE_BIT));
        GLStateManager.glEnable(GL11.GL_BLEND);
        GLStateManager.popState();
        GLStateManager.glEnable(GL11.GL_LINE_SMOOTH);

        releaseCurrentContext();

        activate(contextB);
        assertFalse(GLStateManager.isRecordingDisplayList(),
            "A's parked compilation is not active in a different context");
        assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw(),
            "B's ordinary BufferBuilder draw must not see A's parked capture token");
        drawBufferBuilderLikeUploader(buildTriangleBuffer(), "native-sibling-context-ordinary-draw");
        assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw(),
            "B's ordinary draw leaves the capture stack unchanged");

        final int listIdB = GLStateManager.glGenLists(1);
        assertNotEquals(listIdA, listIdB, "A's unfinished name remains reserved in the shared namespace");
        GLStateManager.glEnable(GL11.GL_LINE_SMOOTH);
        GLStateManager.glScissor(3, 6, 97, 45);
        final int[] scissorBeforeCompile = currentScissorBox();
        GLStateManager.glNewList(listIdB, GL11.GL_COMPILE);
        GLStateManager.pushState(StateSet.forMask(GL11.GL_SCISSOR_BIT));
        GLStateManager.glScissor(11, 13, 31, 37);
        GLStateManager.popState();
        assertTrue(TessellatorManager.shouldInterceptBufferBuilderDraw(),
            "B's own display-list session owns its BufferBuilder draw capture");
        drawBufferBuilderLikeUploader(buildTriangleBuffer(), "native-sibling-context-captured-draw");
        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);
        GLStateManager.glEndList();
        assertFalse(GLStateManager.isRecordingDisplayList(), "ending B's list keeps A's session parked");
        assertTrue(GLStateManager.glIsList(listIdB));
        assertFalse(GLStateManager.glIsList(listIdA), "B's EndList does not publish A's unfinished recorder");
        assertTrue(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH),
            "GL_COMPILE in B records its disable without executing it");

        final CompiledDisplayList listB = GLStateManager.getDisplayList(listIdB);
        assertNotNull(listB);
        assertEquals(contextB.identity, listB.getCompilationIdentity());
        assertEquals(2, countStateSetScopeCommands(listB));
        assertNotNull(listB.getOwnedVbos(), "B's BufferBuilder capture is compiled into a native VBO");
        assertTrue(listB.getCommandCounts().get(GLCommand.DRAW_RANGE) > 0,
            "B's draw is represented by a recorded draw range");
        GLStateManager.glCallList(listIdB);
        assertArrayEquals(scissorBeforeCompile, currentScissorBox(), "B's COW scope restores its scissor state");
        assertFalse(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH), "B's persistent command runs only in B");

        releaseCurrentContext();
        activate(contextA);
        assertTrue(GLStateManager.isRecordingDisplayList(),
            "rebinding A restores only A's still-open display-list session");
        assertFalse(GLStateManager.glIsList(listIdA), "A's list stays unpublished until its own EndList");
        GLStateManager.glEndList();
        assertFalse(GLStateManager.isRecordingDisplayList());
        assertTrue(GLStateManager.glIsList(listIdA));

        final CompiledDisplayList listA = GLStateManager.getDisplayList(listIdA);
        assertNotNull(listA);
        assertEquals(contextA.identity, listA.getCompilationIdentity());
        assertEquals(2, countStateSetScopeCommands(listA));
        assertNotNull(listA.getOwnedVbos(), "the compiled state-only list has an empty VBO owner");
        assertEquals(0, listA.getOwnedVbos().getVBOs().length,
            "B's ordinary draw must not create an A-owned draw range");
        assertTrue(listA.getIndexedBatches().isEmpty(), "B's ordinary draw must not add an A indexed batch");
        assertEquals(0, listA.getCommandCounts().get(GLCommand.DRAW_RANGE),
            "B's ordinary draw must not be appended to A's recorder");
        assertNotEquals(listA.getCommandOpcodes(), listB.getCommandOpcodes(),
            "A's resumed recorder did not absorb B's command stream");
        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);
        GLStateManager.glCallList(listIdA);
        assertTrue(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH), "A's resumed list retains its own persistent command");
        assertTrue(GLStateManager.glIsList(listIdB), "A's EndList leaves B's compiled list in the shared namespace");

        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);
        GLStateManager.glDeleteLists(listIdA, 1);
        GLStateManager.glDeleteLists(listIdB, 1);

        final int canceledList = GLStateManager.glGenLists(1);
        GLStateManager.glNewList(canceledList, GL11.GL_COMPILE_AND_EXECUTE);
        GLStateManager.glEnable(GL11.GL_LINE_SMOOTH);
        final CommandRecorder pausedRecorder = DisplayListManager.pauseRecording();
        assertNotNull(pausedRecorder);
        assertFalse(GLStateManager.isRecordingDisplayList(),
            "the compile-and-execute recorder is paused before its owner is retired");
        assertTrue(DisplayListManager.abortCompilationForContext(contextA.identity),
            "destroy cleanup cancels an owner while its recorder is in the pause window");
        DisplayListManager.resumeRecording(pausedRecorder);
        assertFalse(GLStateManager.isRecordingDisplayList(),
            "resuming a canceled recorder cannot restore its display-list session");
        assertFalse(TessellatorManager.shouldInterceptBufferBuilderDraw(),
            "canceling the owner also removes its draw capture token");
        assertFalse(GLStateManager.glIsList(canceledList), "the canceled compile-and-execute list is never published");
        activate(contextB);
        assertFalse(GLStateManager.glIsList(listIdA));
        assertFalse(GLStateManager.glIsList(listIdB));
        assertNoGlError("per-context display-list sessions across release, sibling compile, and rebind");
    }

    private static void verifyCompiledListReplayAndDeferredVaoDelete() {
        activate(contextA);
        final int vertexBuffer = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER,
            new float[]{-0.75F, -0.75F, 0.0F, 0.75F, -0.75F, 0.0F,
                0.75F, 0.75F, 0.0F, -0.75F, 0.75F, 0.0F}, GL15.GL_STATIC_DRAW);

        final int indexBuffer = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        GLStateManager.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, new int[]{0, 1, 2, 2, 3, 0}, GL15.GL_STATIC_DRAW);
        GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        final int program = createSolidColorProgram();
        final AttribLayoutKey layout = new AttribLayoutKey(
            new int[]{0}, new int[]{3}, new int[]{GL11.GL_FLOAT}, new boolean[]{false});
        final IndexedDrawBatch batch = new IndexedDrawBatch(
            contextA.identity.shareGroupId(), layout, vertexBuffer, indexBuffer);
        final BatchedIndexedDrawCmd draw = new BatchedIndexedDrawCmd();
        draw.fill(batch, GL11.GL_TRIANGLES, 6, GL11.GL_UNSIGNED_INT, 0L);

        final CommandBuffer commandBuffer = new CommandBuffer(8);
        commandBuffer.writeComplexRef(draw);
        final CompiledDisplayList compiledList = new CompiledDisplayList(
            commandBuffer.toBuffer(), commandBuffer.getComplexObjects(), null, List.of(batch), contextA.identity);

        activate(contextA);
        renderAndAssertPixel(contextA, compiledList, program);
        final int vaoA = batch.getVertexArray(contextA.identity);

        activate(contextB);
        renderAndAssertPixel(contextB, compiledList, program);
        final int vaoB = batch.getVertexArray(contextB.identity);

        activate(contextA);
        renderAndAssertPixel(contextA, compiledList, program);
        assertEquals(vaoA, batch.getVertexArray(contextA.identity), "A reuses its compiled-list VAO after B replay");

        activate(contextB);
        compiledList.delete();
        GLStateManager.glUseProgram(0);

        activate(contextA);
        assertTrue(GL30.glIsVertexArray(vaoA),
            "deleting the compiled list in B queues its A-local VAO for the owner context");
        GLStateManager.glUseProgram(0);
        GLStateManager.glBindVertexArray(0);
        assertFalse(GL30.glIsVertexArray(vaoA), "A reclaims the queued compiled-list VAO");
        GLStateManager.glDeleteProgram(program);
        assertNoGlError("compiled indexed display-list replay and deferred VAO deletion");
    }

    private static void retireOwnerContextWhileItsShareGroupRemainsLive() {
        activate(contextA);
        final int ownerBuffer = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, ownerBuffer);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{0.0F, 0.0F, 0.0F}, GL15.GL_STATIC_DRAW);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        final ContextOwnedVertexArray owner = new ContextOwnedVertexArray(contextA.identity.shareGroupId());
        final int ownerVaoA = owner.getOrCreate(contextA.identity,
            () -> configureAttributeZero(ownerBuffer, 3));
        assertTrue(GL30.glIsVertexArray(ownerVaoA), "A's context-local VAO exists before native retirement");

        activate(contextD);
        final int ownerVaoD = owner.getOrCreate(contextD.identity,
            () -> configureAttributeZero(ownerBuffer, 3));
        assertTrue(GL30.glIsVertexArray(ownerVaoD));
        final long shareGroupId = contextD.identity.shareGroupId();
        assertFalse(retireAndDestroy(contextA), "A has no active display-list compilation during retirement");

        assertEquals(1, REGISTRY.liveContextsInShareGroup(shareGroupId).size(),
            "retiring A must leave D's shared object namespace alive");
        assertEquals(contextD.identity, REGISTRY.liveContextsInShareGroup(shareGroupId).get(0));
        assertEquals(ownerVaoD, owner.getOrCreate(contextD.identity,
            () -> { throw new AssertionError("A's retirement must leave D's VAO cached"); }));
        assertTrue(GL30.glIsVertexArray(ownerVaoD));
        owner.delete();
        assertFalse(GL30.glIsVertexArray(ownerVaoD));
        assertFalse(ownerVaoA == ownerVaoD,
            "with A retired, this final owner check concerns D's remaining context-local name");
        GLStateManager.deleteBufferForShareGroup(shareGroupId, ownerBuffer);
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, contextD.framebuffer.framebuffer);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GLStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER),
            "D's framebuffer remains usable after the original share-group owner retires");
        GLStateManager.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
        assertNoGlError("using a live shared context after its original owner retires");
    }

    private static void verifyRetiringNestedParentLeavesChildCompilationActive() {
        activate(contextD);
        final int parentList = GLStateManager.glGenLists(1);
        GLStateManager.glNewList(parentList, GL11.GL_COMPILE);
        GLStateManager.pushState(StateSet.forMask(GL11.GL_ENABLE_BIT));
        GLStateManager.glEnable(GL11.GL_BLEND);

        releaseCurrentContext();
        activate(contextC);
        final int childList = GLStateManager.glGenLists(1);
        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);
        GLStateManager.glNewList(childList, GL11.GL_COMPILE);
        GLStateManager.pushState(StateSet.forMask(GL11.GL_SCISSOR_BIT));
        GLStateManager.glScissor(17, 19, 31, 37);
        GLStateManager.popState();
        GLStateManager.glEnable(GL11.GL_LINE_SMOOTH);
        assertTrue(GLStateManager.isRecordingDisplayList(), "C owns its independent active list session");
        assertFalse(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH), "GL_COMPILE leaves C's persistent command unapplied");

        final GLContextIdentity retiringParent = REGISTRY.identityFor(contextD.token);
        assertNotNull(retiringParent);
        assertTrue(retireAndDestroy(contextD),
            "the destroy hook's owner-abort must remove D's parked compilation session");
        assertTrue(GLStateManager.isRecordingDisplayList(),
            "retiring D's capture must leave C's independent child session active");
        assertEquals(contextC.identity, REGISTRY.currentBinding(),
            "retiring D must preserve C's current context binding");

        GLStateManager.glEndList();
        assertFalse(GLStateManager.isRecordingDisplayList(), "C's EndList closes only C's remaining session");
        assertTrue(GLStateManager.glIsList(childList), "C's child list is published after its parent is retired");
        final CompiledDisplayList compiledChild = GLStateManager.getDisplayList(childList);
        assertNotNull(compiledChild);
        assertEquals(contextC.identity, compiledChild.getCompilationIdentity());
        assertEquals(2, countStateSetScopeCommands(compiledChild));
        GLStateManager.glCallList(childList);
        assertTrue(GL11.glIsEnabled(GL11.GL_LINE_SMOOTH),
            "C's completed child command stream remains executable after D retires");
        GLStateManager.glDisable(GL11.GL_LINE_SMOOTH);
        GLStateManager.glDeleteLists(childList, 1);
        assertFalse(GLStateManager.glIsList(childList));
        assertNoGlError("retiring a nested parent capture while an independent child context continues");
    }

    private static void retireSiblingContextWhileShareGroupRemainsLive() {
        activate(contextA);
        final int sharedBuffer = GLStateManager.glGenBuffers();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, sharedBuffer);
        GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{2.0F, 3.0F, 5.0F}, GL15.GL_STATIC_DRAW);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        assertFalse(retireAndDestroy(contextB), "B has no active display-list compilation during retirement");
        assertEquals(2, REGISTRY.liveContextsInShareGroup(contextA.identity.shareGroupId()).size(),
            "B retirement must leave A and D's shared namespace live");

        activate(contextA);
        assertTrue(GL15.glIsBuffer(sharedBuffer),
            "A must remain usable and retain shared objects after sibling B retires");
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, sharedBuffer);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glDeleteBuffers(sharedBuffer);
        assertNoGlError("sibling-context retirement while A remains usable");
    }

    private static void verifyCowRestoreHookNotifications() {
        activate(contextA);
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        GLStateManager.enableFog();
        GLStateManager.glFogi(GL11.GL_FOG_MODE, GL11.GL_EXP2);
        GLStateManager.glFogf(GL11.GL_FOG_DENSITY, 0.375F);
        GLStateManager.glFogf(GL11.GL_FOG_START, 4.5F);
        GLStateManager.glFogf(GL11.GL_FOG_END, 73.0F);
        GLStateManager.fogColor(0.125F, 0.25F, 0.5F, 0.75F);

        final int originalTexture = GLStateManager.glGenTextures();
        final int temporaryTexture = GLStateManager.glGenTextures();
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, originalTexture);
        final FogSnapshot originalFog = captureFogState();
        final HookCapture capture = new HookCapture();
        final GLSMHooks.TextureBindSyncCallback previousSync = GLSMHooks.textureBindSyncCallback;
        GLSMHooks.FOG_STATE_CHANGE.addListener(event -> {
            if (activeHookCapture != null && activeHookCapture.enabled) {
                activeHookCapture.order.add("fog");
                activeHookCapture.fogSnapshots.add(captureFogState());
            }
        });
        GLSMHooks.TEXTURE_BIND.addListener(event -> {
            if (activeHookCapture != null && activeHookCapture.enabled) {
                activeHookCapture.order.add("texture-bind");
                activeHookCapture.textureBinds.add(
                    new TextureBindSnapshot(event.unit, event.target, event.textureId));
            }
        });
        GLSMHooks.textureBindSyncCallback = (unit, textureId) -> {
            if (activeHookCapture != null && activeHookCapture.enabled) {
                activeHookCapture.order.add("texture-sync");
                activeHookCapture.textureSyncs.add(new TextureSyncSnapshot(unit, textureId));
            }
            if (previousSync != null) {
                previousSync.onTextureBound(unit, textureId);
            }
        };
        activeHookCapture = capture;

        try {
            GLStateManager.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_FOG_BIT | GL11.GL_TEXTURE_BIT);
            GLStateManager.disableFog();
            GLStateManager.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
            GLStateManager.glFogf(GL11.GL_FOG_DENSITY, 1.25F);
            GLStateManager.glFogf(GL11.GL_FOG_START, 9.0F);
            GLStateManager.glFogf(GL11.GL_FOG_END, 19.0F);
            GLStateManager.fogColor(0.875F, 0.625F, 0.375F, 0.25F);
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, temporaryTexture);

            assertFalse(GLStateManager.glIsEnabled(GL11.GL_FOG));
            assertEquals(GL11.GL_LINEAR, GLStateManager.getFogState().getFogMode());
            assertEquals(temporaryTexture, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));

            capture.enabled = true;
            GLStateManager.glPopAttrib();
            capture.enabled = false;

            assertEquals(originalFog, captureFogState(), "COW pop must restore fog parameters and the fog enable bit");
            assertEquals(originalTexture, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                "COW pop must restore the unit-zero driver binding");
            assertEquals(List.of(originalFog), capture.fogSnapshots,
                "fog restoration must publish its restored values exactly once");
            assertEquals(List.of(new TextureBindSnapshot(0, GL11.GL_TEXTURE_2D, originalTexture)), capture.textureBinds,
                "unit-zero restoration must publish its actual target and binding exactly once");
            assertEquals(List.of(new TextureSyncSnapshot(0, originalTexture)), capture.textureSyncs,
                "unit-zero vanilla mirror synchronization must receive the restored binding exactly once");
            assertEquals(List.of("texture-bind", "texture-sync", "fog"), capture.order,
                "texture observers must see the restored binding before the fog snapshot");
            assertNoGlError("COW pop hook notifications for fog and unit-zero texture restoration");
        } finally {
            capture.enabled = false;
            activeHookCapture = null;
            GLSMHooks.textureBindSyncCallback = previousSync;
        }
    }

    private static FogSnapshot captureFogState() {
        final var fog = GLStateManager.getFogState();
        final var color = fog.getFogColor();
        return new FogSnapshot(
            GLStateManager.getFogMode().isEnabled(),
            fog.getFogMode(),
            fog.getDensity(),
            fog.getStart(),
            fog.getEnd(),
            color.x,
            color.y,
            color.z,
            fog.getFogAlpha()
        );
    }

    private static void configureAttributeZero(int vertexBuffer, int size) {
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GLStateManager.glVertexAttribPointer(0, size, GL11.GL_FLOAT, false, 12, 0L);
        GLStateManager.glEnableVertexAttribArray(0);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static BufferBuilder buildTriangleBuffer() {
        final BufferBuilder buffer = new BufferBuilder(256);
        buffer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);
        buffer.pos(-0.5D, -0.5D, 0.0D).color(255, 0, 0, 255).endVertex();
        buffer.pos(0.5D, -0.5D, 0.0D).color(0, 255, 0, 255).endVertex();
        buffer.pos(0.0D, 0.5D, 0.0D).color(0, 0, 255, 255).endVertex();
        return buffer;
    }

    /** Mirrors the active-capture branch in MixinWorldVertexBufferUploader for this unbootstrapped fixture. */
    private static void drawBufferBuilderLikeUploader(BufferBuilder buffer, String debugSource) {
        if (TessellatorManager.shouldInterceptBufferBuilderDraw()) {
            TessellatorManager.interceptBufferBuilderDraw(buffer);
        } else {
            VanillaBufferBuilderRenderer.draw(buffer, debugSource);
        }
    }

    private static int vertexAttributeSize(int vao) {
        GLStateManager.glBindVertexArray(vao);
        final int size = GL20.glGetVertexAttribi(0, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE);
        GLStateManager.glBindVertexArray(0);
        return size;
    }

    private static void renderAndAssertPixel(TestContext context, CompiledDisplayList displayList, int program) {
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, context.framebuffer.framebuffer);
        GLStateManager.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GLStateManager.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GLStateManager.glViewport(0, 0, WIDTH, HEIGHT);
        GLStateManager.glDisable(GL11.GL_BLEND);
        GLStateManager.glDisable(GL11.GL_CULL_FACE);
        GLStateManager.glDisable(GL11.GL_DEPTH_TEST);
        GLStateManager.glDisable(GL11.GL_DITHER);
        GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
        GLStateManager.glColorMask(true, true, true, true);
        GLStateManager.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        GLStateManager.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GLStateManager.glUseProgram(program);
        displayList.render();
        GLStateManager.glFinish();

        final ByteBuffer pixel = BufferUtils.createByteBuffer(4);
        GLStateManager.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        assertEquals(255, pixel.get(0) & 255, "the center readback must contain the solid red draw");
        assertEquals(64, pixel.get(1) & 255, "the center readback must contain the solid green draw");
        assertEquals(0, pixel.get(2) & 255, "the center readback must contain no blue draw");
        assertEquals(255, pixel.get(3) & 255, "the center readback must be opaque");
        assertNoGlError("indexed display-list rendering in context " + context.identity.generation());
    }

    private static int createSolidColorProgram() {
        final int vertex = compileShader(GL20.GL_VERTEX_SHADER,
            "#version 330 compatibility\nlayout(location = 0) in vec3 position;\n"
                + "void main() { gl_Position = vec4(position, 1.0); }\n");
        final int fragment = compileShader(GL20.GL_FRAGMENT_SHADER,
            "#version 330 compatibility\nout vec4 fragmentColor;\n"
                + "void main() { fragmentColor = vec4(1.0, 0.25, 0.0, 1.0); }\n");
        final int program = GLStateManager.glCreateProgram();
        GLStateManager.glAttachShader(program, vertex);
        GLStateManager.glAttachShader(program, fragment);
        GLStateManager.glLinkProgram(program);
        final int linked = GLStateManager.glGetProgrami(program, GL20.GL_LINK_STATUS);
        final String linkLog = GL20.glGetProgramInfoLog(program);
        GLStateManager.glDeleteShader(vertex);
        GLStateManager.glDeleteShader(fragment);
        assertEquals(GL11.GL_TRUE, linked, linkLog);
        return program;
    }

    private static int compileShader(int type, String source) {
        final int shader = GLStateManager.glCreateShader(type);
        GLStateManager.glShaderSource(shader, source);
        GLStateManager.glCompileShader(shader);
        assertEquals(GL11.GL_TRUE, GLStateManager.glGetShaderi(shader, GL20.GL_COMPILE_STATUS),
            GL20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static Framebuffer createFramebuffer() {
        final int texture = GLStateManager.glGenTextures();
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GLStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GLStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GLStateManager.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WIDTH, HEIGHT,
            0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        final int framebuffer = GLStateManager.glGenFramebuffers();
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GLStateManager.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, texture, 0);
        final int status = GLStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, status,
            "hidden-context FBO must be complete before readback");
        GLStateManager.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GLStateManager.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GLStateManager.glViewport(0, 0, WIDTH, HEIGHT);
        return new Framebuffer(framebuffer, texture);
    }

    private static void setAttribState(boolean blend, boolean depth, boolean scissor, int depthFunc,
        boolean depthMask, boolean red, boolean green, boolean blue, boolean alpha,
        int x, int y, int width, int height) {
        setEnabled(GL11.GL_BLEND, blend);
        setEnabled(GL11.GL_DEPTH_TEST, depth);
        setEnabled(GL11.GL_SCISSOR_TEST, scissor);
        GLStateManager.glDepthFunc(depthFunc);
        GLStateManager.glDepthMask(depthMask);
        GLStateManager.glColorMask(red, green, blue, alpha);
        GLStateManager.glScissor(x, y, width, height);
    }

    private static void assertAttribState(boolean blend, boolean depth, boolean scissor, int depthFunc,
        boolean depthMask, boolean red, boolean green, boolean blue, boolean alpha,
        int x, int y, int width, int height) {
        assertEquals(blend, GL11.glIsEnabled(GL11.GL_BLEND));
        assertEquals(depth, GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        assertEquals(scissor, GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
        assertEquals(depthFunc, GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        assertEquals(depthMask, GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));

        final ByteBuffer colorMask = BufferUtils.createByteBuffer(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorMask);
        assertEquals(red, colorMask.get(0) != 0);
        assertEquals(green, colorMask.get(1) != 0);
        assertEquals(blue, colorMask.get(2) != 0);
        assertEquals(alpha, colorMask.get(3) != 0);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            final IntBuffer scissorBox = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
            assertEquals(x, scissorBox.get(0));
            assertEquals(y, scissorBox.get(1));
            assertEquals(width, scissorBox.get(2));
            assertEquals(height, scissorBox.get(3));
        }
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GLStateManager.glEnable(capability);
        } else {
            GLStateManager.glDisable(capability);
        }
    }

    private static void assertNoGlError(String operation) {
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), operation + " must finish without a driver error");
    }

    private static void hintCompatibilityContext() {
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_FALSE);
    }

    private static TestContext createContext(long share) {
        final long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "Actinium GL integration", MemoryUtil.NULL, share);
        assertNotEquals(MemoryUtil.NULL, window,
            "GLFW must create a hidden OpenGL 3.3 compatibility context");
        return new TestContext(new Object(), window);
    }

    private static void activate(TestContext context) {
        final GLContextIdentity previousBinding = REGISTRY.currentBinding();
        GLFW.glfwMakeContextCurrent(context.window);
        if (!openGLInitialized) {
            GL.create();
            final var provider = GL.getFunctionProvider();
            if (provider == null) {
                throw new IllegalStateException("LWJGL did not expose the active GLFW context's function provider");
            }
            openGLInitialized = true;
        }
        GL.createCapabilities();
        assertEquals(context.window, GLFW.glfwGetCurrentContext(), "GLFW made the requested context current");
        final GLContextIdentity targetIdentity = REGISTRY.identityFor(context.token);
        final boolean changedBinding = previousBinding == null || !previousBinding.equals(targetIdentity);
        if (previousBinding != null && changedBinding) {
            DisplayListManager.onContextUnbound(previousBinding);
        }
        final GLContextIdentity boundIdentity = REGISTRY.bindCurrent(context.token, GLFW.glfwGetCurrentContext());
        if (context.identity == null) {
            context.identity = boundIdentity;
        } else {
            assertEquals(context.identity, boundIdentity);
        }
        if (changedBinding) {
            DisplayListManager.onContextBound(boundIdentity);
        }
    }

    private static void releaseCurrentContext() {
        final GLContextIdentity previousBinding = REGISTRY.currentBinding();
        GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
        GL.setCapabilities(null);
        DisplayListManager.onContextUnbound(previousBinding);
        REGISTRY.releaseCurrent();
    }

    private static boolean retireAndDestroy(TestContext context) {
        if (context == null || context.destroyed) {
            return false;
        }
        final GLContextIdentity identity = REGISTRY.identityFor(context.token);
        final boolean abortedCompilation = DisplayListManager.abortCompilationForContext(identity);
        GLFW.glfwDestroyWindow(context.window);
        context.destroyed = true;
        assertEquals(identity, REGISTRY.retire(context.token));
        return abortedCompilation;
    }

    private static final class TestContext {
        private final Object token;
        private final long window;
        private GLContextIdentity identity;
        private Framebuffer framebuffer;
        private boolean destroyed;

        private TestContext(Object token, long window) {
            this.token = token;
            this.window = window;
        }
    }

    private static final class HookCapture {
        private final List<String> order = new ArrayList<>();
        private final List<FogSnapshot> fogSnapshots = new ArrayList<>();
        private final List<TextureBindSnapshot> textureBinds = new ArrayList<>();
        private final List<TextureSyncSnapshot> textureSyncs = new ArrayList<>();
        private boolean enabled;
    }

    private record FogSnapshot(boolean enabled, int mode, float density, float start, float end,
                               double red, double green, double blue, float alpha) {}

    private record TextureBindSnapshot(int unit, int target, int textureId) {}

    private record TextureSyncSnapshot(int unit, int textureId) {}

    private record Framebuffer(int framebuffer, int colorTexture) {}
}
