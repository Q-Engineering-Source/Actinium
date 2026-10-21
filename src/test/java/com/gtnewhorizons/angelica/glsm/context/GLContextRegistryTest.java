package com.gtnewhorizons.angelica.glsm.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises context identity, thread transfer, sharing, and retirement without requiring an OpenGL driver. */
class GLContextRegistryTest {

    @Test
    void aReleasedContextKeepsItsIdentityWhenAnotherThreadTakesOwnership() throws InterruptedException {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        final Object context = new Object();
        final GLContextIdentity original = registry.bindCurrent(context, 0xA11CEL);
        registry.releaseCurrent();

        final AtomicReference<GLContextIdentity> rebound = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread nextOwner = new Thread(() -> {
            try {
                rebound.set(registry.bindCurrent(context, 0xA11CEL));
                assertEquals(original, registry.current(0xA11CEL, true));
                registry.releaseCurrent();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "context-registry-transfer");
        nextOwner.start();
        nextOwner.join();

        assertNull(failure.get());
        assertEquals(original, rebound.get());
        assertTrue(registry.isLive(original));
    }

    @Test
    void identityLookupDoesNotRegisterUnknownTokensAndStopsAfterRetirement() {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        final Object context = new Object();

        assertNull(registry.identityFor(context));
        assertTrue(registry.liveContextsInShareGroup(1L).isEmpty());

        final GLContextIdentity identity = registry.bindCurrent(context, 0xA12CEL);
        assertEquals(identity, registry.identityFor(context));
        registry.releaseCurrent();
        assertEquals(identity, registry.identityFor(context));

        registry.retire(context);
        assertNull(registry.identityFor(context));
    }

    @Test
    void aRecycledNativeHandleGetsANewGenerationAndShareGroup() {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        final Object firstOwner = new Object();
        final GLContextIdentity first = registry.bindCurrent(firstOwner, 0xBEEFL);
        registry.releaseCurrent();
        assertEquals(first, registry.retire(firstOwner));

        final Object secondOwner = new Object();
        final GLContextIdentity second = registry.bindCurrent(secondOwner, 0xBEEFL);

        assertNotEquals(first.generation(), second.generation());
        assertNotEquals(first.shareGroupId(), second.shareGroupId());
        assertFalse(registry.isLive(first));
        assertTrue(registry.isLive(second));
    }

    @Test
    void replacingTheNativeContextBehindOneTokenNotifiesRetirementOutsideTheRegistryLock() throws InterruptedException {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        final Object context = new Object();
        final AtomicReference<GLContextIdentity> retired = new AtomicReference<>();
        final AtomicReference<Boolean> listenerCouldQueryRegistry = new AtomicReference<>(false);
        registry.setRetirementListener(identity -> {
            retired.set(identity);
            final CountDownLatch queried = new CountDownLatch(1);
            final Thread observer = new Thread(() -> {
                listenerCouldQueryRegistry.set(!registry.isLive(identity));
                queried.countDown();
            }, "context-registry-retirement-observer");
            observer.start();
            try {
                assertTrue(queried.await(2, TimeUnit.SECONDS), "retirement callback ran while the registry lock was held");
                observer.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while checking retirement callback lock ownership", exception);
            }
        });

        final GLContextIdentity oldIdentity = registry.bindCurrent(context, 0xA11CEL);
        registry.releaseCurrent();
        final GLContextIdentity newIdentity = registry.bindCurrent(context, 0xA11CFEL);

        assertEquals(oldIdentity, retired.get());
        assertTrue(listenerCouldQueryRegistry.get());
        assertFalse(registry.isLive(oldIdentity));
        assertTrue(registry.isLive(newIdentity));
        assertNotEquals(oldIdentity.generation(), newIdentity.generation());
        assertNotEquals(oldIdentity.shareGroupId(), newIdentity.shareGroupId());
        assertEquals(newIdentity, registry.current(0xA11CFEL, true));
    }

    @Test
    void sharedContextsKeepTheShareGroupAliveUntilItsLastContextRetires() {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        final Object parent = new Object();
        final Object child = new Object();
        final GLContextIdentity parentIdentity = registry.bindCurrent(parent, 0xCAFE01L);
        registry.releaseCurrent();
        registry.registerSharedContext(child, 0xCAFE02L, parent);

        final GLContextIdentity childIdentity = registry.bindCurrent(child, 0xCAFE02L);
        assertEquals(parentIdentity.shareGroupId(), childIdentity.shareGroupId());
        registry.releaseCurrent();
        registry.retire(parent);

        assertTrue(registry.isLive(childIdentity));
        assertEquals(List.of(childIdentity), registry.liveContextsInShareGroup(childIdentity.shareGroupId()));
        registry.retire(child);

        final GLContextIdentity later = registry.bindCurrent(new Object(), 0xCAFE01L);
        assertNotEquals(childIdentity.shareGroupId(), later.shareGroupId());
    }

    @Test
    void additionalRetirementListenersCanObserveAndReleaseShareGroupOwners() throws Exception {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        final List<GLContextIdentity> primaryNotifications = new ArrayList<>();
        final List<GLContextIdentity> additionalNotifications = new ArrayList<>();
        registry.setRetirementListener(primaryNotifications::add);
        final AutoCloseable subscription = registry.addRetirementListener(additionalNotifications::add);

        final Object firstContext = new Object();
        final GLContextIdentity first = registry.bindCurrent(firstContext, 0xE001L);
        registry.releaseCurrent();
        registry.retire(firstContext);

        assertEquals(List.of(first), primaryNotifications);
        assertEquals(List.of(first), additionalNotifications);
        assertTrue(registry.liveContextsInShareGroup(first.shareGroupId()).isEmpty());

        subscription.close();
        final Object secondContext = new Object();
        final GLContextIdentity second = registry.bindCurrent(secondContext, 0xE002L);
        registry.releaseCurrent();
        registry.retire(secondContext);

        assertEquals(List.of(first, second), primaryNotifications);
        assertEquals(List.of(first), additionalNotifications);
    }

    @Test
    void aDirectNativeContextSwitchCannotReuseThePreviousThreadBinding() {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        final GLContextIdentity first = registry.bindCurrent(new Object(), 0xD001L);

        assertThrows(IllegalStateException.class, () -> registry.current(0xD002L, true));
        assertEquals(first, registry.current(0L, true));
    }

    @Test
    void retiringAnIntermediateSharedContextDoesNotSplitItsDescendants() {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        final Object a = new Object();
        final Object b = new Object();
        final Object c = new Object();
        final Object d = new Object();
        final GLContextIdentity aIdentity = registry.bindCurrent(a, 0xDA01L);
        registry.releaseCurrent();
        registry.registerSharedContext(b, 0xDA02L, a);
        registry.registerSharedContext(c, 0xDA03L, b);
        final GLContextIdentity cIdentity = registry.bindCurrent(c, 0xDA03L);
        registry.releaseCurrent();

        registry.retire(b);
        registry.registerSharedContext(d, 0xDA04L, c);

        assertEquals(aIdentity.shareGroupId(), cIdentity.shareGroupId());
        assertTrue(registry.liveContextsInShareGroup(cIdentity.shareGroupId()).stream()
            .allMatch(identity -> identity.shareGroupId() == cIdentity.shareGroupId()));
        assertEquals(cIdentity.shareGroupId(), registry.bindCurrent(d, 0xDA04L).shareGroupId());
    }

    @Test
    void aMissingNativeContextClearsTheThreadBindingInsteadOfReturningTheOldIdentity() {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        registry.bindCurrent(new Object(), 0xD00EL);

        assertNull(registry.current(0L, false));
        assertThrows(IllegalStateException.class, () -> registry.current(0xD00EL, true));
    }

    @Test
    void invalidDestroyedHandlesCannotBeBound() {
        final GLContextRegistry registry = new GLContextRegistryImpl();

        assertThrows(IllegalArgumentException.class, () -> registry.bindCurrent(new Object(), -1L));
    }

    @Test
    void aContextCannotBeCurrentOnTwoThreadsAtOnce() throws InterruptedException {
        final GLContextRegistry registry = new GLContextRegistryImpl();
        final Object context = new Object();
        registry.bindCurrent(context, 0xD00DL);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread secondOwner = new Thread(() -> {
            try {
                registry.bindCurrent(context, 0xD00DL);
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "context-registry-double-owner");
        secondOwner.start();
        secondOwner.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        registry.releaseCurrent();
    }
}
