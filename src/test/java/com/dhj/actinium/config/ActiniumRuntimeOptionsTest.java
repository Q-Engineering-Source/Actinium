package com.dhj.actinium.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiniumRuntimeOptionsTest {
    private static final String PERF_DEBUG_PROPERTY = "actinium.perfDebug";
    private static final String PBR_DEBUG_PROPERTY = "actinium.pbrDebug";
    private static final String WORKER_THREADS_PROPERTY = "actinium.workerThreads";
    private final String originalPerfDebugProperty = System.getProperty(PERF_DEBUG_PROPERTY);
    private final String originalPbrDebugProperty = System.getProperty(PBR_DEBUG_PROPERTY);
    private final String originalWorkerThreadsProperty = System.getProperty(WORKER_THREADS_PROPERTY);

    @AfterEach
    void restoreDebugProperties() {
        restoreProperty(PERF_DEBUG_PROPERTY, this.originalPerfDebugProperty);
        restoreProperty(PBR_DEBUG_PROPERTY, this.originalPbrDebugProperty);
        restoreProperty(WORKER_THREADS_PROPERTY, this.originalWorkerThreadsProperty);
    }

    @Test
    void usesConfiguredValueWithoutAnExplicitOverride() {
        System.clearProperty(PERF_DEBUG_PROPERTY);

        assertTrue(ActiniumRuntimeOptions.resolvePerfDebugEnabled(true));
        assertFalse(ActiniumRuntimeOptions.resolvePerfDebugEnabled(false));
    }

    @Test
    void explicitOverrideTakesPriorityOverConfiguredValue() {
        System.setProperty(PERF_DEBUG_PROPERTY, "true");
        assertTrue(ActiniumRuntimeOptions.resolvePerfDebugEnabled(false));

        System.setProperty(PERF_DEBUG_PROPERTY, "false");
        assertFalse(ActiniumRuntimeOptions.resolvePerfDebugEnabled(true));
    }

    @Test
    void usesConfiguredPbrDebugValueWithoutAnExplicitOverride() {
        System.clearProperty(PBR_DEBUG_PROPERTY);

        assertTrue(ActiniumRuntimeOptions.resolvePbrDebugEnabled(true));
        assertFalse(ActiniumRuntimeOptions.resolvePbrDebugEnabled(false));
    }

    @Test
    void explicitPbrDebugOverrideTakesPriorityOverConfiguredValue() {
        System.setProperty(PBR_DEBUG_PROPERTY, "true");
        assertTrue(ActiniumRuntimeOptions.resolvePbrDebugEnabled(false));

        System.setProperty(PBR_DEBUG_PROPERTY, "false");
        assertFalse(ActiniumRuntimeOptions.resolvePbrDebugEnabled(true));
    }

    @Test
    void workerThreadCountFallsBackToTheConfiguredValue() {
        System.clearProperty(WORKER_THREADS_PROPERTY);

        assertEquals(0, ActiniumRuntimeOptions.resolveWorkerThreadCount(0));
        assertEquals(6, ActiniumRuntimeOptions.resolveWorkerThreadCount(6));
    }

    @Test
    void explicitWorkerThreadOverrideTakesPriorityOverTheConfiguredValue() {
        System.setProperty(WORKER_THREADS_PROPERTY, "3");

        assertEquals(3, ActiniumRuntimeOptions.resolveWorkerThreadCount(0));
        assertEquals(3, ActiniumRuntimeOptions.resolveWorkerThreadCount(6));
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
