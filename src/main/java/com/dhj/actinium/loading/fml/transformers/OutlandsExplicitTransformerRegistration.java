package com.dhj.actinium.loading.fml.transformers;

import com.dhj.actinium.startup.SdlGpuBootstrap;
import top.outlands.foundation.IExplicitTransformer;
import top.outlands.foundation.TransformerDelegate;

import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Registers explicit transformers only when the Outlands Foundation API is available. */
public final class OutlandsExplicitTransformerRegistration {
    private static final String TRANSFORMER_DELEGATE_RESOURCE =
        "top/outlands/foundation/TransformerDelegate.class";

    private OutlandsExplicitTransformerRegistration() {
    }

    public static void register() {
        if (OutlandsExplicitTransformerRegistration.class.getClassLoader()
            .getResource(TRANSFORMER_DELEGATE_RESOURCE) == null) {
            return;
        }
        Outlands.register();
    }

    private static final class Outlands {
        private static final String CONTEXT_ATTRIBS = "org.lwjgl.opengl.ContextAttribs";
        private static final List<String> LIFECYCLE_TARGETS = List.of(
            "org.lwjgl.opengl.Display",
            "org.lwjgl.opengl.DrawableGL",
            "org.lwjgl.opengl.ContextGL"
        );

        private static final IExplicitTransformer LIFECYCLE =
            new Adapter(new LwjglContextLifecycleTransformer());
        private static final IExplicitTransformer VERSION =
            new Adapter(new LwjglContextVersionTransformer());
        private static final IExplicitTransformer SDL_HOST =
            new Adapter(new SdlGpuHostTransformer());

        private static synchronized void register() {
            Map<String, PriorityQueue<IExplicitTransformer>> registry =
                TransformerDelegate.getExplicitTransformers();
            for (String target : LIFECYCLE_TARGETS) {
                register(registry, LIFECYCLE, target);
            }
            register(registry, VERSION, CONTEXT_ATTRIBS);
            if (SdlGpuBootstrap.isSupported()) {
                for (String target : SdlGpuHostTransformer.TARGETS) {
                    register(registry, SDL_HOST, target);
                }
            }
        }

        private static void register(
            Map<String, PriorityQueue<IExplicitTransformer>> registry,
            IExplicitTransformer transformer,
            String target
        ) {
            PriorityQueue<IExplicitTransformer> transformers = registry.get(target);
            if (transformers == null
                || transformers.stream().noneMatch(registered -> registered == transformer)) {
                TransformerDelegate.registerExplicitTransformer(transformer, target);
            }
        }

        private record Adapter(ExplicitClassTransformer delegate) implements IExplicitTransformer {
            @Override
            public byte[] transform(byte[] basicClass) {
                return delegate.transform(basicClass);
            }

            @Override
            public int getPriority() {
                return delegate.getPriority();
            }
        }
    }
}
