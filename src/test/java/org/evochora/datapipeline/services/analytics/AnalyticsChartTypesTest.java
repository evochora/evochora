package org.evochora.datapipeline.services.analytics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.evochora.datapipeline.api.analytics.IAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds every configured analytics plugin against the chart types the frontend can render.
 * <p>
 * A chart type is a string travelling from a plugin through the manifest into the browser, where
 * an unknown one produces a console warning and an empty diagram — a failure nobody sees until
 * someone opens that metric. The registry the charts register themselves in is the only place the
 * types are declared, so this test reads them from there rather than repeating them, and asks the
 * plugins for their manifest rather than reading their source.
 */
class AnalyticsChartTypesTest {

    private static final String PLUGINS = "pipeline.services.analytics-indexer-1.options.plugins";
    private static final Path CHART_DIR = Path.of("src/main/resources/web/analyzer/js/charts");
    private static final Pattern REGISTRATION =
            Pattern.compile("ChartRegistry\\.register\\(\\s*['\"]([^'\"]+)['\"]");

    @Test
    @Tag("integration")
    void everyPluginAsksForAChartTheFrontendCanRender() throws Exception {
        List<String> registered = registeredChartTypes();
        assertThat(registered).as("chart types registered in %s", CHART_DIR).isNotEmpty();

        Config config = ConfigFactory.load();
        List<? extends Config> plugins = config.getConfigList(PLUGINS);
        assertThat(plugins).as("plugins configured under %s", PLUGINS).isNotEmpty();

        for (Config pluginConfig : plugins) {
            String className = pluginConfig.getString("className");
            ManifestEntry entry = manifestOf(className, pluginConfig.getConfig("options"));
            if (entry == null || entry.visualization == null) {
                continue;
            }
            assertThat(entry.visualization.type)
                    .as("%s asks for a chart type the frontend registers", className)
                    .isIn(registered);
        }
    }

    private static ManifestEntry manifestOf(String className, Config options) throws Exception {
        IAnalyticsPlugin plugin = (IAnalyticsPlugin) Class.forName(className)
                .getDeclaredConstructor().newInstance();
        plugin.configure(options);
        plugin.initialize(null);
        return plugin.getManifestEntry();
    }

    private static List<String> registeredChartTypes() throws IOException {
        List<String> types = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CHART_DIR)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".js")).toList()) {
                Matcher matcher = REGISTRATION.matcher(Files.readString(file));
                while (matcher.find()) {
                    types.add(matcher.group(1));
                }
            }
        }
        return types;
    }
}
