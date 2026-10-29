package com.dhj.actinium.mixin.compat.dh;

import com.dhj.actinium.compat.dh.DhGlBufferDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.atomic.AtomicInteger;

/** Thin lifecycle hooks for DH GLBuffer byte-capacity diagnostics. */
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLBuffer", remap = false)
public abstract class DhGLBufferDiagnosticsMixin {
    @Shadow(remap = false)
    protected volatile int id;

    @Shadow(remap = false)
    protected int size;

    @Shadow(remap = false)
    public static AtomicInteger bufferCount;

    @Inject(method = "destroyOldAndCreate(Z)V", at = @At("RETURN"), remap = false)
    private void actinium$recordBufferCreation(CallbackInfo callbackInfo) {
        DhGlBufferDiagnostics.created(this.id, bufferCount.get());
    }

    @Inject(method = "uploadBufferData(Ljava/nio/ByteBuffer;I)V", at = @At("RETURN"), remap = false)
    private void actinium$recordBufferDataCapacity(CallbackInfo callbackInfo) {
        DhGlBufferDiagnostics.capacityReported(this.id, this.size, bufferCount.get());
    }

    @Inject(method = "uploadBufferStorage(Ljava/nio/ByteBuffer;)V", at = @At("RETURN"), remap = false)
    private void actinium$recordBufferStorageCapacity(CallbackInfo callbackInfo) {
        DhGlBufferDiagnostics.capacityReported(this.id, this.size, bufferCount.get());
    }

    @Inject(method = "uploadSubData(Ljava/nio/ByteBuffer;II)V", at = @At("RETURN"), remap = false)
    private void actinium$recordSubDataCapacity(CallbackInfo callbackInfo) {
        DhGlBufferDiagnostics.capacityReported(this.id, this.size, bufferCount.get());
    }

    @Inject(method = "destroyBufferIdNow(ILjava/lang/String;)V", at = @At("RETURN"), remap = false)
    private static void actinium$recordBufferDeletion(int deletedId, String cause, CallbackInfo callbackInfo) {
        DhGlBufferDiagnostics.deleted(deletedId, bufferCount.get());
    }
}
