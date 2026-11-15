package com.dhj.actinium.architecture;

import com.dhj.actinium.architecture.ArchScan.Dir;
import com.dhj.actinium.architecture.ArchScan.ScannedClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the mixin package isolation rule from {@code AGENTS.md}: helper classes declared inside a
 * mixin package must not be referenced from outside the mixin packages, because mixin classes are
 * only loadable by the mixin framework. A reference from a target class or its callers resolves the
 * helper in the plain class loader and fails with {@code IllegalClassLoadError}.
 *
 * <p>Accessors and invokers are exempt: {@code AGENTS.md} documents them as the supported way to
 * expose mixin-provided members to normal code. Packages named {@code mixinterface} are exempt for
 * the same reason.</p>
 */
class MixinPackageIsolationTest {

    private static final String MIXIN_PACKAGE = "com.dhj.actinium.mixin.";
    private static final String MIX_INTERFACE = ".mixinterface.";

    private static final List<String> ACCESSOR_MARKERS = List.of("Accessor", "Invoker");

    @Test
    void mixinHelpersAreNotReferencedFromOutside() {
        final Set<String> helpers = new LinkedHashSet<>();
        for (ScannedClass scanned : ArchScan.in(Dir.MAIN)) {
            if (!isMixinPackage(scanned.name()) || isExempt(scanned.name())) continue;
            helpers.add(scanned.name());
        }
        assertFalse(helpers.isEmpty(), "No mixin helper classes found; the scan paths are likely wrong");

        final List<String> violations = new ArrayList<>();
        for (ScannedClass origin : ArchScan.in(Dir.MAIN)) {
            if (isMixinPackage(origin.name())) continue;
            for (String reference : origin.references()) {
                if (helpers.contains(reference)) {
                    violations.add("  " + origin.name() + " -> " + reference);
                }
            }
        }

        assertTrue(violations.isEmpty(),
            "Mixin package helpers must not be referenced from outside the mixin packages (they fail to load):\n"
                + String.join("\n", violations));
    }

    private static boolean isMixinPackage(String className) {
        return className.startsWith(MIXIN_PACKAGE);
    }

    private static boolean isExempt(String className) {
        if (className.contains(MIX_INTERFACE)) return true;
        final String simpleName = className.substring(className.lastIndexOf('.') + 1);
        for (String marker : ACCESSOR_MARKERS) {
            if (simpleName.startsWith(marker) || simpleName.endsWith(marker)) return true;
        }
        return false;
    }
}
