package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationComponentCapabilitiesRespVO.Component;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationComponentCapabilitiesRespVO.Status;
import org.junit.jupiter.api.Test;

import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationComponentCapabilitiesServiceTest {
    private final IntegrationComponentCapabilitiesService service = new IntegrationComponentCapabilitiesService();

    @Test void coversEveryPaletteEntryOnceForEveryEngineAndMode() {
        for (String engine : new String[]{"SPARK", "FLINK", "DATAX"}) {
            for (String mode : new String[]{"OFFLINE", "REALTIME"}) {
                var result = service.get(engine, mode);
                assertEquals(engine, result.engine()); assertEquals(mode, result.mode());
                assertEquals(49, result.components().size());
                assertEquals(49, result.components().stream().map(Component::componentCode).distinct().count());
                result.components().stream().filter(value -> value.status() != Status.SUPPORTED)
                        .forEach(value -> assertFalse(value.reason().isBlank(), value.componentCode()));
                if ("REALTIME".equals(mode) && !"FLINK".equals(engine))
                    assertTrue(result.components().stream().allMatch(value -> value.status() == Status.UNAVAILABLE));
            }
        }
    }

    @Test void keepsTheFlinkProductBoundaryWithoutRemovingSparkFrameworkCoverage() {
        var components = service.get("FLINK", "REALTIME").components().stream()
                .collect(Collectors.toMap(Component::componentCode, Function.identity()));
        assertEquals(Status.UNAVAILABLE, components.get("calculator").status());
        assertEquals(Status.UNAVAILABLE, components.get("add-constant").status());
        assertEquals(Status.UNAVAILABLE, components.get("set-field-value").status());
        assertEquals(Status.UNAVAILABLE, components.get("multi-join").status());
        assertEquals(Status.UNAVAILABLE, components.get("sort-records").status());
        assertEquals(Status.PARTIAL, components.get("dedup-records").status());
        assertEquals(Status.PARTIAL, components.get("row-to-column").status());
        assertEquals(Status.UNAVAILABLE, components.get("sub-pipeline").status());
        assertEquals(Status.PARTIAL, components.get("read-kafka").status());
        assertEquals(Status.UNAVAILABLE, components.get("write-kafka").status());
        assertTrue(components.get("read-kafka").reason().contains("SASL/TLS认证尚未适配"));
        assertEquals(Status.UNAVAILABLE, components.get("read-rabbitmq").status());
        assertEquals(Status.UNAVAILABLE, components.get("read-rocketmq").status());
        assertEquals(Status.UNAVAILABLE, components.get("write-rocketmq").status());
        assertEquals(Status.PARTIAL, components.get("read-tdengine").status());
        assertEquals(Status.PARTIAL, components.get("java-script").status());
        assertEquals(Status.PARTIAL, components.get("custom-token").status());
        var batch = service.get("SPARK", "OFFLINE").components().stream()
                .collect(Collectors.toMap(Component::componentCode, Function.identity()));
        assertEquals(Status.PARTIAL, batch.get("read-csv").status());
        assertEquals(Status.PARTIAL, batch.get("read-json").status());
        assertEquals(Status.PARTIAL, batch.get("java-script").status());
        assertEquals(Status.PARTIAL, batch.get("custom-token").status());
        for (String code : new String[]{"read-hdfs", "write-hdfs", "read-hive", "write-hive", "read-redis", "write-redis", "read-ftp", "write-ftp", "rest-api", "read-webservice"})
            assertEquals(Status.PARTIAL, batch.get(code).status(), code);
        assertEquals(Status.PARTIAL, batch.get("row-to-column").status());
        for (String code : new String[]{"read-odps", "write-odps"}) {
            assertEquals(Status.PARTIAL, batch.get(code).status());
            assertTrue(batch.get(code).reason().contains("云端真实读写尚待验收"));
            assertEquals(Status.UNAVAILABLE, components.get(code).status());
        }
    }

    @Test void bothFlinkModesExposeOnlyTheTwelveCurrentReferenceComponentsIncludingToken() {
        for (String mode : new String[]{"OFFLINE", "REALTIME"}) {
            var components = service.get("FLINK", mode).components().stream().collect(Collectors.toMap(Component::componentCode, Function.identity()));
            assertEquals(IntegrationComponentCapabilitiesService.FLINK_COMPONENTS,
                    components.values().stream().filter(component -> component.status() != Status.UNAVAILABLE).map(Component::componentCode).collect(Collectors.toSet()));
            assertEquals(37, components.values().stream().filter(component -> component.status() == Status.UNAVAILABLE).count());
            assertTrue(components.get("custom-token").reason().contains("opaque"));
        }
        assertEquals(Status.PARTIAL, service.get("FLINK", "REALTIME").components().stream().filter(component -> component.componentCode().equals("custom-token")).findFirst().orElseThrow().status());
    }

    @Test void dataxExposesExactlyTheThreeReferenceComponents() {
        var enabled = service.get("DATAX", "OFFLINE").components().stream().filter(component -> component.status() != Status.UNAVAILABLE)
                .map(Component::componentCode).collect(Collectors.toSet());
        assertEquals(java.util.Set.of("read-db", "dedup-records", "insert-db"), enabled);
    }

    @Test void rejectsInvalidEngineOrMode() {
        assertThrows(RuntimeException.class, () -> service.get("unknown", "OFFLINE"));
        assertThrows(RuntimeException.class, () -> service.get("SPARK", null));
    }
}
