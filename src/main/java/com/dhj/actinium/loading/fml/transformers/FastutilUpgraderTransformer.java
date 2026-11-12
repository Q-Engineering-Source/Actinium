package com.dhj.actinium.loading.fml.transformers;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * Replaces fastutil 7.x class bytecode (bundled with vanilla Forge) with fastutil 8.x bytecode
 * from the embedded jar at META-INF/actinium_libs/fastutil.jar.
 *
 * <p>Vanilla Forge 1.12.2 ships fastutil 7.1.0 on the launch classpath. fastutil 8 adds
 * primitive-specialised overloads (computeIfAbsent, getOrDefault, Int2LongMaps.fastIterator, etc.)
 * that the codebase relies on. Rather than manipulating URLClassPath internals, this transformer
 * intercepts bytecode loading for the it.unimi.dsi.fastutil package and returns the 8.x version.</p>
 */
public final class FastutilUpgraderTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    private static final String FASTUTIL_PREFIX = "it.unimi.dsi.fastutil.";
    private static final String EMBEDDED_JAR = "/META-INF/actinium_libs/fastutil.jar";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (!transformedName.startsWith(FASTUTIL_PREFIX)) {
            return basicClass;
        }
        final byte[] upgraded = loadFromEmbedded(transformedName);
        if (upgraded != null) {
            return upgraded;
        }
        return basicClass;
    }

    private static byte[] loadFromEmbedded(String className) {
        final String entryName = className.replace('.', '/') + ".class";
        final InputStream jarStream = FastutilUpgraderTransformer.class.getResourceAsStream(EMBEDDED_JAR);
        if (jarStream == null) {
            return null;
        }
        try (ZipInputStream zip = new ZipInputStream(jarStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = zip.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                    return baos.toByteArray();
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to read fastutil 8 class {} from embedded jar", className, e);
        }
        return null;
    }
}
