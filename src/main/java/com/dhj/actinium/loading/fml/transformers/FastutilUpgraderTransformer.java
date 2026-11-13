package com.dhj.actinium.loading.fml.transformers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * Replaces fastutil 7.x classes (bundled with vanilla Forge) with the fastutil 8.x classes shipped
 * in this jar under {@code META-INF/actinium_libs/fastutil8}.
 *
 * <p>fastutil 8 adds the primitive-specialised overloads the rendering code relies on
 * ({@code computeIfAbsent}, {@code getOrDefault}, {@code Int2LongMaps.fastIterator}, ...). Vanilla
 * Forge puts fastutil 7.1.0 on the launch classpath ahead of the mod jar, and rewriting
 * {@code URLClassPath} instead of the transformer pipeline breaks loader caches, so the bytes are
 * swapped here before {@code defineClass}.</p>
 *
 * <p>Each class is read from its own jar entry rather than from an embedded jar stream, so loading
 * one fastutil class does not decompress the rest of the library.</p>
 */
public final class FastutilUpgraderTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    private static final String FASTUTIL_PREFIX = "it.unimi.dsi.fastutil.";
    private static final String RESOURCE_ROOT = "/META-INF/actinium_libs/fastutil8/";
    private static final String RESOURCE_SUFFIX = ".bin";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (transformedName == null || !transformedName.startsWith(FASTUTIL_PREFIX)) {
            return basicClass;
        }
        final byte[] upgraded = readClass(transformedName.replace('.', '/'));
        return upgraded != null ? upgraded : basicClass;
    }

    private static byte[] readClass(String entryName) {
        final String resource = RESOURCE_ROOT + entryName + RESOURCE_SUFFIX;
        try (InputStream in = FastutilUpgraderTransformer.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            LOGGER.error("Failed to read {} from the jar resources", resource, e);
            return null;
        }
    }
}
