package com.dhj.actinium.hud.runtime;

import com.dhj.actinium.hud.frame.FrameTimelineImpl;
import com.dhj.actinium.hud.layout.IndicatorLayout;
import com.dhj.actinium.hud.metric.IndicatorMetrics;
import com.dhj.actinium.hud.metric.IndicatorMetricsImpl;
import com.dhj.actinium.hud.metric.IndicatorSnapshot;
import com.dhj.actinium.hud.render.IndicatorHudRenderer;
import com.dhj.actinium.hud.render.IndicatorHudRendererImpl;
import com.dhj.actinium.hud.render.IndicatorTextBuilder;
import com.dhj.actinium.hud.system.SystemMetricsSource;
import com.dhj.actinium.hud.system.SystemMetricsSources;
import com.dhj.actinium.runtime.ActiniumRuntime;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.util.List;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions.PerformanceIndicatorSettings.refreshMaxMillis;
import static dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions.PerformanceIndicatorSettings.refreshMinMillis;

/**
 * Implementation of {@link PerformanceIndicator} on top of the stored configuration.
 *
 * <p>The configuration is read once per frame, so a saved value reaches the indicator on the next
 * frame. The layout is only rebuilt when the stored row ids change, and the formatted lines are kept
 * until the next publish produces a new snapshot, which also refreshes their labels after a language
 * change.
 *
 * <p>Hardware sampling follows the master switch and the loaded world. It starts on the render thread
 * after the GL context of that thread has been confirmed and the renderer string has been read for the
 * graphics card selection, reschedules the existing worker on an interval change, and stops when the
 * indicator is switched off or the world is left. Hiding the indicator through F1, F3 or an open screen changes nothing but its
 * visibility: the frames keep running through the metrics and keep their extremes.
 */
public final class PerformanceIndicatorImpl implements PerformanceIndicator {
    private static final Logger LOGGER = LogManager.getLogger("Actinium PerformanceIndicator");
    private final IndicatorMetrics metrics = new IndicatorMetricsImpl(new FrameTimelineImpl());
    private final IndicatorHudRenderer renderer = new IndicatorHudRendererImpl();
    private final IndicatorTextBuilder textBuilder = new IndicatorTextBuilder();
    private final SystemMetricsSource systemSource = SystemMetricsSources.create();

    private boolean enabled;
    private int refreshMillis;
    private List<String> configuredRows;
    private IndicatorLayout layout = IndicatorLayout.fromConfig(List.of());

    private IndicatorSnapshot textSnapshot;
    private IndicatorLayout textLayout;
    private List<String> textRows = List.of();

    private boolean sampling;
    private int samplingRefreshMillis;
    private boolean startFailureLogged;

    @Override
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            beginFrame();
        } else {
            endFrame();
        }
    }

    @Override
    public void shutdown() {
        stopSampling();
        this.systemSource.close();
    }

    private void beginFrame() {
        final Minecraft minecraft = Minecraft.getMinecraft();
        readConfig();
        syncSampling(minecraft);
        this.metrics.onFrameStart(System.nanoTime(), minecraft.world, this.enabled);
    }

    private void endFrame() {
        final Minecraft minecraft = Minecraft.getMinecraft();
        final IndicatorSnapshot snapshot = this.metrics.onFrameEnd(System.nanoTime(), minecraft.world, this.enabled,
            this.refreshMillis, this.systemSource.latest());
        if (!isVisible(minecraft)) {
            return;
        }
        this.renderer.render(minecraft, new ScaledResolution(minecraft), textRows(snapshot));
    }

    private void readConfig() {
        final SodiumGameOptions.PerformanceIndicatorSettings settings = ActiniumRuntime.options().performanceIndicator;
        this.enabled = settings.enabled;
        this.refreshMillis = Math.max(refreshMinMillis(), Math.min(refreshMaxMillis(), settings.refreshIntervalMillis));
        final List<String> rows = settings.rows;
        if (!rows.equals(this.configuredRows)) {
            this.configuredRows = List.copyOf(rows);
            this.layout = IndicatorLayout.fromConfig(this.configuredRows);
        }
    }

    private boolean isVisible(Minecraft minecraft) {
        return this.enabled
            && minecraft.world != null
            && !minecraft.gameSettings.hideGUI
            && !minecraft.gameSettings.showDebugInfo
            && minecraft.currentScreen == null
            && !this.layout.enabledRows().isEmpty();
    }

    private List<String> textRows(IndicatorSnapshot snapshot) {
        if (this.textLayout != this.layout || this.textSnapshot != snapshot) {
            this.textLayout = this.layout;
            this.textSnapshot = snapshot;
            this.textRows = this.textBuilder.build(this.layout, snapshot);
        }
        return this.textRows;
    }

    private void syncSampling(Minecraft minecraft) {
        if (!this.enabled || minecraft.world == null) {
            stopSampling();
            return;
        }
        if (this.sampling) {
            if (this.samplingRefreshMillis != this.refreshMillis) {
                try {
                    this.systemSource.setRefreshIntervalMillis(this.refreshMillis);
                    this.samplingRefreshMillis = this.refreshMillis;
                    this.startFailureLogged = false;
                } catch (RuntimeException failure) {
                    logStartFailure(failure);
                }
            }
            return;
        }
        startSampling();
    }

    private void startSampling() {
        if (!RENDER_BACKEND.hasContextOnThread()) {
            return;
        }
        final String rendererName = RENDER_BACKEND.getString(GL11.GL_RENDERER);
        try {
            this.systemSource.start(rendererName, this.refreshMillis);
        } catch (RuntimeException failure) {
            logStartFailure(failure);
            return;
        }
        this.sampling = true;
        this.samplingRefreshMillis = this.refreshMillis;
        this.startFailureLogged = false;
    }

    private void logStartFailure(RuntimeException failure) {
        if (this.startFailureLogged) {
            return;
        }
        this.startFailureLogged = true;
        LOGGER.warn("Hardware metric sampling operation failed; the next frame retries", failure);
    }

    private void stopSampling() {
        if (!this.sampling) {
            return;
        }
        this.sampling = false;
        this.samplingRefreshMillis = 0;
        this.systemSource.stop();
    }
}
