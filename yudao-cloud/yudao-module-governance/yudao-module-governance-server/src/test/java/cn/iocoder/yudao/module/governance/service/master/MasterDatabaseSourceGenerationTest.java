package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Resolver binding and JSON contracts only; no database connections or multi-node consensus claims. */
class MasterDatabaseSourceGenerationTest {
    private static final String URL = "jdbc:mariadb://fixture.invalid:43322/orders";
    private static final String USER = "fixture_reader";
    private static final String TARGET = ConfiguredSourceGenerations.targetFingerprint(ProductTypeEnum.MARIADB, URL, USER);
    private static final String PROVIDER_REVISION = "2026-09-25T10:00:00.123456";
    private static final SourceGenerationPin G1 = new SourceGenerationPin("Fixture_Source_Metadata",
            "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222");
    private static final SourceGenerationPin G2 = new SourceGenerationPin(G1.schema(), G1.instanceId(),
            "33333333-3333-4333-8333-333333333333");

    @Test void defaultEmptyRegistryDoesNotChangeProviderRevisionOrInventPin() {
        var resolver = resolver(dto(PROVIDER_REVISION));
        var route = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertThat(route.getConfigurationRevision()).isEqualTo(PROVIDER_REVISION);
        assertThat(route.getProviderConfigurationRevision()).isEqualTo(PROVIDER_REVISION);
        assertThat(route.getSourceGenerationPin()).isNull();
    }

    @Test void boundedQuerySpecRetainsManagedUrlAndRejectsRestoredOrRotatedRoutes() {
        var resolver = resolver(dto(PROVIDER_REVISION), registry("fixture", G1));
        var route = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        var query = resolver.queryConnectionSpec(route);
        assertThat(query.url()).isEqualTo(ConfiguredSourceGenerations.managedJdbcUrl(URL));
        assertThat(query.configurationGeneration()).isEqualTo(route.getConfigurationRevision());
        var restored = JsonUtils.parseObject(JsonUtils.toJsonString(route), MasterDatabaseResolver.ConnectionInfo.class);
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.queryConnectionSpec(restored));
        ReflectionTestUtils.setField(resolver, "configuredSourceGenerations", registry("rotated", G2));
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.queryConnectionSpec(route));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingProviderRevisionIsNotPromotedToManagedProof(String revision) {
        var resolver = resolver(dto(revision), registry("fixture", G1));
        var route = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertThat(route.getSourceGenerationPin()).isEqualTo(G1);
        assertThat(route.getConfigurationRevision()).isEqualTo(revision);
    }

    @Test void independentNodesWithEqualPinsHaveEqualRevisions() {
        var firstRegistry = registry("node-a-label", G1);
        var secondRegistry = registry("node-b-label", G1);
        var first = resolver(dto(PROVIDER_REVISION), firstRegistry).resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        var second = resolver(dto(PROVIDER_REVISION), secondRegistry).resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertThat(firstRegistry).isNotSameAs(secondRegistry);
        assertThat(first.getProviderConfigurationRevision()).isEqualTo(PROVIDER_REVISION);
        assertThat(first.getSourceGenerationPin()).isEqualTo(second.getSourceGenerationPin())
                .isNotSameAs(second.getSourceGenerationPin());
        assertThat(first.getConfigurationRevision()).matches("source-v1:[0-9a-f]{64}")
                .isEqualTo(second.getConfigurationRevision());
    }

    @Test void schemaInstanceGenerationAndProviderRevisionEachChangeEffectiveRevision() {
        var original = resolver(dto(PROVIDER_REVISION), registry("fixture", G1))
                .resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS).getConfigurationRevision();
        for (var changed : List.of(
                new SourceGenerationPin("fixture_source_metadata", G1.instanceId(), G1.generation()),
                new SourceGenerationPin(G1.schema(), "44444444-4444-4444-8444-444444444444", G1.generation()),
                G2)) {
            var route = resolver(dto(PROVIDER_REVISION), registry("fixture", changed))
                    .resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
            assertThat(route.getConfigurationRevision()).matches("source-v1:[0-9a-f]{64}").isNotEqualTo(original);
        }
        assertThat(resolver(dto("provider-next"), registry("fixture", G1))
                .resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS).getConfigurationRevision()).isNotEqualTo(original);
    }

    @Test void registryCopiesConfigurationAndPreservesExactSchemaAndUuids() {
        var config = config(G1);
        var mutable = new HashMap<String, DbSwitchProperties.SourceGeneration>();
        mutable.put("fixture", config);
        var registry = new ConfiguredSourceGenerations(mutable);
        var resolver = resolver(dto(PROVIDER_REVISION), registry);
        var before = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        config.setSchema("changed_metadata");
        config.setInstanceId("55555555-5555-4555-8555-555555555555");
        config.setGeneration(G2.generation());
        mutable.clear();
        var after = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertThat(after.getSourceGenerationPin()).isSameAs(before.getSourceGenerationPin()).isEqualTo(G1);
        assertThat(after.getSourceGenerationPin().schema()).isEqualTo("Fixture_Source_Metadata");
        assertThat(after.getSourceGenerationPin().instanceId()).isEqualTo(G1.instanceId());
        assertThat(after.getSourceGenerationPin().generation()).isEqualTo(G1.generation());
        assertThat(after.getConfigurationRevision()).isEqualTo(before.getConfigurationRevision());
        assertThat(registry.isCurrent(TARGET, G1.fingerprint())).isTrue();
        var replaced = resolver(dto(PROVIDER_REVISION), new ConfiguredSourceGenerations(Map.of("fixture", config)))
                .resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertThat(replaced.getConfigurationRevision()).isNotEqualTo(before.getConfigurationRevision());
    }

    @Test void runtimePinDoesNotLeakIntoJsonOrToStringAndCannotBeRestoredFromJson() {
        var resolver = resolver(dto(PROVIDER_REVISION), registry("fixture", G1));
        var route = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        assertRuntimePinHidden(route);
        String json = JsonUtils.toJsonString(route);
        var restored = JsonUtils.parseObject(json, MasterDatabaseResolver.ConnectionInfo.class);
        assertThat(restored.getConfigurationRevision()).isEqualTo(route.getConfigurationRevision());
        assertThat(restored.getSchema()).isEqualTo("orders");
        assertThat(restored.getSourceGenerationPin()).isNull();
        assertRejectedBeforePoolCreation(resolver, restored);

        var injected = JsonUtils.parseMap(json);
        injected.put("sourceGenerationPin", Map.of("schema", G1.schema(),
                "instanceId", G1.instanceId(), "generation", G1.generation()));
        var untrusted = JsonUtils.parseObject(JsonUtils.toJsonString(injected), MasterDatabaseResolver.ConnectionInfo.class);
        assertThat(untrusted.getSourceGenerationPin()).isNull();
        assertRejectedBeforePoolCreation(resolver, untrusted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-config", "rotated", "removed"})
    void oldRouteCannotCreatePoolAfterRegistryReplacement(String replacement) {
        var resolver = resolver(dto(PROVIDER_REVISION), registry("fixture", G1));
        var oldRoute = resolver.resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        var next = switch (replacement) {
            case "same-config" -> registry("next", G1);
            case "rotated" -> registry("next", G2);
            default -> ConfiguredSourceGenerations.empty();
        };
        ReflectionTestUtils.setField(resolver, "configuredSourceGenerations", next);
        assertRejectedBeforePoolCreation(resolver, oldRoute);
    }

    @Test void routeFromAnUnmanagedResolverCannotBypassNewRegistry() {
        var oldRoute = resolver(dto(PROVIDER_REVISION)).resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
        var current = resolver(dto(PROVIDER_REVISION), registry("fixture", G1));
        assertRejectedBeforePoolCreation(current, oldRoute);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void springContextInjectsDeploymentSnapshotAndRetainsJsonContract(boolean managed) {
        var api = api(dto(PROVIDER_REVISION));
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDbSwitchAutoConfiguration.class))
                .withBean(DatabaseApi.class, () -> api).withBean(MasterDatabaseResolver.class);
        if (managed) {
            runner = runner.withPropertyValues("yudao.dbswitch.source-generations.fixture.product=MARIADB",
                    "yudao.dbswitch.source-generations.fixture.target-fingerprint=" + TARGET,
                    "yudao.dbswitch.source-generations.fixture.schema=" + G1.schema(),
                    "yudao.dbswitch.source-generations.fixture.instance-id=" + G1.instanceId(),
                    "yudao.dbswitch.source-generations.fixture.generation=" + G1.generation(),
                    "yudao.dbswitch.source-generations.fixture.restore-rotation-required=true");
        }
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var route = context.getBean(MasterDatabaseResolver.class).resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS);
            var registry = context.getBean(ConfiguredSourceGenerations.class);
            if (managed) {
                assertThat(route.getSourceGenerationPin()).isSameAs(registry.find(ProductTypeEnum.MARIADB, URL, USER).orElseThrow())
                        .isEqualTo(G1);
                assertThat(route.getConfigurationRevision()).matches("source-v1:[0-9a-f]{64}");
                assertRuntimePinHidden(route);
                var properties = context.getBean(DbSwitchProperties.class);
                properties.getSourceGenerations().get("fixture").setGeneration(G2.generation());
                assertThat(context.getBean(MasterDatabaseResolver.class).resolve(4L, QUALITY_CHECK_OBJECT_NOT_EXISTS)
                        .getConfigurationRevision()).isEqualTo(route.getConfigurationRevision());
            } else {
                assertThat(registry.find(ProductTypeEnum.MARIADB, URL, USER)).isEmpty();
                assertThat(route.getSourceGenerationPin()).isNull();
                assertThat(route.getConfigurationRevision()).isEqualTo(PROVIDER_REVISION);
            }
            verify(api, times(managed ? 2 : 1)).getDatabaseConnection(4L);
        });
    }

    private static void assertRuntimePinHidden(MasterDatabaseResolver.ConnectionInfo route) {
        assertThat(route.getSourceGenerationPin()).isEqualTo(G1);
        assertThat(JsonUtils.toJsonString(route)).doesNotContain("sourceGenerationPin", G1.schema(), G1.instanceId(), G1.generation());
        assertThat(route.toString()).doesNotContain("sourceGenerationPin", G1.schema(), G1.instanceId(), G1.generation());
        assertThat(route.getSourceGenerationPin().toString()).doesNotContain(G1.schema(), G1.instanceId(), G1.generation());
    }

    private static void assertRejectedBeforePoolCreation(MasterDatabaseResolver resolver, MasterDatabaseResolver.ConnectionInfo route) {
        try (var factory = mockStatic(DataSourceUtils.class)) {
            assertThatIllegalArgumentException().isThrownBy(() -> resolver.createDataSource(route))
                    .withMessageContaining("configured source generation");
            factory.verifyNoInteractions();
        }
    }

    private static ConfiguredSourceGenerations registry(String label, SourceGenerationPin pin) {
        return new ConfiguredSourceGenerations(Map.of(label, config(pin)));
    }

    private static DbSwitchProperties.SourceGeneration config(SourceGenerationPin pin) {
        var config = new DbSwitchProperties.SourceGeneration();
        config.setProduct(ProductTypeEnum.MARIADB); config.setTargetFingerprint(TARGET);
        config.setSchema(pin.schema()); config.setInstanceId(pin.instanceId()); config.setGeneration(pin.generation());
        config.setRestoreRotationRequired(true);
        return config;
    }

    private static DatabaseConnectionRespDTO dto(String revision) {
        var dto = new DatabaseConnectionRespDTO();
        dto.setId(4L); dto.setProductType("MARIADB"); dto.setJdbcUrl(URL); dto.setUsername(USER);
        dto.setSchema("orders"); dto.setUsageType(1); dto.setConfigurationRevision(revision);
        return dto;
    }

    private static DatabaseApi api(DatabaseConnectionRespDTO dto) {
        var api = mock(DatabaseApi.class);
        when(api.getDatabaseConnection(4L)).thenReturn(success(dto));
        return api;
    }

    private static MasterDatabaseResolver resolver(DatabaseConnectionRespDTO dto) {
        var resolver = new MasterDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "databaseApi", api(dto));
        return resolver;
    }

    private static MasterDatabaseResolver resolver(DatabaseConnectionRespDTO dto, ConfiguredSourceGenerations registry) {
        var resolver = resolver(dto);
        ReflectionTestUtils.setField(resolver, "configuredSourceGenerations", registry);
        return resolver;
    }
}
