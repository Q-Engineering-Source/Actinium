package com.dhj.actinium.compat.sodium;

import com.dhj.actinium.gui.ActiniumGameOptionPages;
import com.dhj.actinium.runtime.ActiniumRuntime;
import net.irisshaders.iris.compat.sodium.IrisConfigEntryPoint;
import dhj.embeddedt.embeddium.api.options.structure.StandardOptions;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import dhj.embeddedt.embeddium.impl.gui.options.CommonOptionPages;
import dhj.embeddedt.embeddium.api.options.structure.OptionPage;

import java.util.ArrayList;
import java.util.List;

/**
 * Actinium's own built-in option page collection: returns embeddium
 * {@link OptionPage} forms of the Actinium video option pages and the Iris
 * compatibility pages directly, without any Sodium config model conversion.
 */
public final class ActiniumOptionPages {
    private ActiniumOptionPages() {
    }

    /** Builds all built-in pages Actinium currently exposes (embeddium model). */
    public static List<OptionPage> builtInPages() {
        return builtInPages(ActiniumRuntime.options());
    }

    /** Builds the built-in pages exposed by the supplied configuration snapshot. */
    static List<OptionPage> builtInPages(SodiumGameOptions options) {
        List<OptionPage> pages = new ArrayList<>(List.of(
                ActiniumGameOptionPages.general(),
                ActiniumGameOptionPages.quality(),
                CommonOptionPages.performance(options),
                ActiniumGameOptionPages.advanced(),
                ActiniumGameOptionPages.fpsReducer(),
                ActiniumGameOptionPages.debug()));
        pages.addAll(new IrisConfigEntryPoint().createPages());
        return List.copyOf(retainEnabledPages(pages, options));
    }

    /**
     * Returns the candidate pages the supplied configuration exposes. While
     * {@code enableDebugTab} is off the DEBUG page is dropped here, so it never
     * reaches the tab rail, the search index, or the apply/undo sweep. The
     * decision stays separate from the page builders because those need a client
     * locale and GL context, which the unit tests deliberately do without.
     */
    static List<OptionPage> retainEnabledPages(List<OptionPage> pages, SodiumGameOptions options) {
        if (options.enableDebugTab) {
            return pages;
        }
        return pages.stream()
                .filter(page -> !StandardOptions.Pages.DEBUG.equals(page.getId()))
                .toList();
    }
}
