package com.dhj.actinium.mixin.mod.extrautils2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;
import java.util.function.Function;

/**
 * Fixes the ConcurrentModificationException crash while building chunk meshes for Extra
 * Utilities 2 blocks (e.g. an ender lilly) under Actinium.
 *
 * <p>{@code XUBlockStatic$3} bakes its quad lists lazily inside plain {@code HashMap}s via
 * {@code computeIfAbsent}; vanilla only calls {@code getQuads} from the single chunk rebuild
 * worker, while Actinium meshes on several worker threads and portal view passes can rebuild
 * the same section while the main pass is still building it, so two threads race inside the
 * map. Both call sites are redirected to serialize on the receiver map's own monitor, which
 * keeps first-bake results consistent (and deduplicated) without changing the returned quads.
 * Remapping stays enabled: the target is an Extra Utilities 2 class, but {@code getQuads}
 * implements Minecraft's {@code IBakedModel}, so its name is the searge
 * {@code func_188616_a} in the production jar and must go through the refmap.
 */
@Mixin(targets = "com.rwtema.extrautils2.backend.XUBlockStatic$3")
public abstract class MixinXUBlockStaticLazyQuads {

    @Redirect(
            method = "getQuads",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/HashMap;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"),
            require = 2)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object celeritas$synchronizedComputeIfAbsent(HashMap map, Object key, Function mappingFunction) {
        synchronized (map) {
            return map.computeIfAbsent(key, mappingFunction);
        }
    }
}
