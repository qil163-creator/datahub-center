package cn.iocoder.yudao.module.dataforge.service.execution;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Structural/export contract only; PromQL and alert timing must additionally pass promtool. */
class RunMonitoringAssetsTest {
    @Test
    void rulesOnlyReferenceMetricsActuallyExportedByCurrentCode() throws Exception {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new RunMetrics(registry);
            String exported = registry.scrape();
            String rules = Files.readString(root().resolve("script/monitoring/dataforge-run.rules.yml"));
            Pattern.compile("\\bdataforge_run_[a-z_]+\\b").matcher(rules).results()
                    .map(match -> match.group()).distinct()
                    .forEach(metric -> assertThat(exported).as(metric).contains("# TYPE " + metric + " "));
        } finally { registry.close(); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyDeliveredAlertHasAnExecutablePromtoolFixture() throws Exception {
        Path root = root();
        Map<String, Object> rules = new Yaml().load(Files.readString(root.resolve("script/monitoring/dataforge-run.rules.yml")));
        var cases = new ArrayList<Map<String, Object>>();
        try (var fixtures = Files.newDirectoryStream(root.resolve("script/monitoring"), "dataforge-run*.test.yml")) {
            for (Path fixture : fixtures) {
                Map<String, Object> tests = new Yaml().load(Files.readString(fixture));
                cases.addAll((List<Map<String, Object>>) tests.get("tests"));
            }
        }
        assertThat(cases.stream().map(test -> test.get("name"))).contains(
                "missing age gauge must not silently remove the node from fresh snapshots",
                "invalid age is unavailable and cannot create a fresh snapshot");
        var group = ((List<Map<String, Object>>) rules.get("groups")).getFirst();
        var alertNames = ((List<Map<String, Object>>) group.get("rules")).stream()
                .filter(rule -> rule.containsKey("alert")).map(rule -> rule.get("alert")).toList();
        var exercised = cases.stream()
                .flatMap(test -> ((List<Map<String, Object>>) test.get("alert_rule_test")).stream())
                .filter(test -> !((List<?>) test.get("exp_alerts")).isEmpty())
                .map(test -> test.get("alertname")).distinct().toList();
        assertThat(alertNames).hasSize(11).containsExactlyInAnyOrderElementsOf(exercised);
        Map<String, Object> config = new Yaml().load(Files.readString(root.resolve("script/monitoring/prometheus-dataforge.yml.example")));
        assertThat((List<String>) config.get("rule_files")).contains("dataforge-run.rules.yml");
    }

    private Path root() {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("script/monitoring/dataforge-run.rules.yml"))) path = path.getParent();
        assertThat(path).as("repository monitoring assets").isNotNull();
        return path;
    }
}
