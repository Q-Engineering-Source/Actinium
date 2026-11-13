package com.dhj.actinium.loading.fml.transformers;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * A class transformer that also supports Outlands' target-specific, single-argument entry point.
 * Implementations remain loadable on legacy Forge, which provides only the three-argument entry.
 */
public interface ExplicitClassTransformer extends IClassTransformer {
    /** Transforms a class whose target name was fixed during explicit registration. */
    byte[] transform(byte[] basicClass);
}
