package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformDefinition;
import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformer;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorRunBinding;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.handler.MigrationHandler;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MigrationRequestTest {
    @Test void perTableDorisOptionsSurviveRequestSnapshotAndJacksonContract() {
        var request = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        var mapping = new MapperConfig();
        mapping.setTargetTableName("chosen_rows");
        mapping.setTargetTableComment("目标表");
        var options = new cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions(
                null, "HASH", List.of("id"), "FIXED", 2, 1);
        mapping.setDorisOptions(options);
        source.setConfigMap(Map.of("source_rows", mapping));
        request.getSource().add(source);
        var frozen = request.snapshot();
        mapping.setTargetTableName("later");
        mapping.setDorisOptions(null);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var restored = mapper.readValue(mapper.writeValueAsString(frozen), MigrationRequest.class)
                .getSource().getFirst().getConfigMap().get("source_rows");
        assertEquals("chosen_rows", restored.getTargetTableName());
        assertEquals("目标表", restored.getTargetTableComment());
        assertEquals(options, restored.getDorisOptions());
        assertThrows(UnsupportedOperationException.class, () -> restored.getDorisOptions().distributionColumns().clear());
    }

    @Test void greenplumDistributionIsDefensivelyCopiedAndJsonCompatible() {
        var request = new MigrationRequest();
        request.getTarget().setTargetProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.GREENPLUM);
        var keys = new ArrayList<>(List.of("Id", "part")); request.getTarget().setDistributedKeys(keys);
        var frozen = request.snapshot(); keys.clear(); request.getTarget().setDistributedKeys(List.of("other"));
        assertEquals(List.of("Id", "part"), frozen.getTarget().getDistributedKeys());
        assertThrows(UnsupportedOperationException.class, () -> frozen.getTarget().getDistributedKeys().clear());
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var decoded = mapper.readValue(mapper.writeValueAsString(frozen), MigrationRequest.class);
        assertEquals(List.of("Id", "part"), decoded.getTarget().getDistributedKeys());
        assertTrue(mapper.readValue("{\"target\":{}}", MigrationRequest.class).getTarget().getDistributedKeys().isEmpty());
        assertTrue(mapper.readValue("{\"target\":{\"distributedKeys\":null}}", MigrationRequest.class).getTarget().getDistributedKeys().isEmpty());
    }

    @Test void invalidDistributionConfigurationFailsBeforeAnyConnection() {
        var request = new MigrationRequest();
        request.getTarget().setDistributedKeys(List.of("id"));
        try (var sources = mockStatic(DataSourceUtils.class); var runtime = DbSwitchRuntime.standalone()) {
            assertThrows(IllegalArgumentException.class, () -> new MigrationService(request, null, null, runtime));
            request.getTarget().setTargetProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.GREENPLUM);
            request.getTarget().setDistributedKeys(List.of("id", "id"));
            assertThrows(IllegalArgumentException.class, () -> new MigrationService(request, null, null, runtime));
            request.getTarget().setDistributedKeys(List.of("id")); request.getTarget().setChangeDataSync(true);
            assertThrows(IllegalArgumentException.class, () -> new MigrationService(request, null, null, runtime));
            sources.verifyNoInteractions();
        }
    }

    @Test void tidbCursorInsufficientSourceCapacityFailsBeforeAnyDataSourceOrTargetEffect() {
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();request.getSource().add(source);
        source.setSourceProductType(cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TIDB);source.setPoolSize(1);
        request.getTarget().setChangeDataSync(true);request.getTarget().setChangeDataSyncType("INCREASE_COLUMN");
        try(var sources=mockStatic(DataSourceUtils.class);var runtime=DbSwitchRuntime.standalone()) {
            assertThrows(IllegalArgumentException.class,()->new MigrationService(request,null,null,runtime));
            sources.verifyNoInteractions();
        }
    }

    @Test void declaredTransformWithoutCallbackIsRejectedBeforeAnyConnectionOrWrite() {
        var request = new MigrationRequest();
        request.getTarget().setTransformDefinition(new RecordTransformDefinition("mask", "1", "a".repeat(64)));
        var runtime = mock(DbSwitchRuntime.class);
        try (var dataSources = mockStatic(DataSourceUtils.class)) {
            assertThrows(IllegalArgumentException.class, () -> new MigrationService(request, null, null, runtime));
            dataSources.verifyNoInteractions();
            verifyNoInteractions(runtime);
        }
    }

    @Test void directTableHandlerCannotBypassMissingTransformCallbackCheck() {
        var request = new MigrationRequest();
        request.getSource().add(new SourceDataSourceProperties());
        request.getTarget().setTransformDefinition(RecordTransformDefinition.legacy());
        var source = mock(HikariDataSource.class);
        var target = mock(HikariDataSource.class);
        try (var runtime = DbSwitchRuntime.standalone()) {
            assertThrows(IllegalArgumentException.class, () -> MigrationHandler.createInstance(
                    new TableDescription(), request, 0, source, target, null, runtime));
            verifyNoInteractions(source, target);
        }
    }

    @Test void callbackOnlyKeepsLegacyIdentityAndDeclaredIdentityIsFrozen() {
        var request = new MigrationRequest();
        RecordTransformer callback = (schema, table, fields, row) -> row;
        try (var runtime = DbSwitchRuntime.standalone()) {
            var legacy = new MigrationService(request, null, callback, runtime);
            var legacySnapshot = (MigrationRequest) ReflectionTestUtils.getField(legacy, "properties");
            assertEquals(RecordTransformDefinition.legacy(), legacySnapshot.getTarget().getTransformDefinition());
            assertNull(request.getTarget().getTransformDefinition(), "Normalization must not modify caller configuration");
            var definition = new RecordTransformDefinition("mask", "1", "a".repeat(64));
            request.getTarget().setTransformDefinition(definition);
            var declared = new MigrationService(request, null, callback, runtime);
            request.getTarget().setTransformDefinition(null);
            var declaredSnapshot = (MigrationRequest) ReflectionTestUtils.getField(declared, "properties");
            assertEquals(definition, declaredSnapshot.getTarget().getTransformDefinition());
        }
    }

    @Test void callerEditsCannotChangeAnAlreadyCreatedRun() {
        var request = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        var mapping = new MapperConfig();
        mapping.setRegexColumnMapper(new ArrayList<>(List.of(new PatternMapper("before", "target"))));
        source.setConfigMap(new HashMap<>(Map.of("records", mapping)));
        source.setSourcePrimaryKeys(new ArrayList<>(List.of("id")));
        request.getSource().add(source);
        request.getTarget().setCustomDdlMap(new HashMap<>(Map.of("records", "CREATE TABLE records(id integer)")));
        var run = new MigrationService(request, null);
        var captured = (MigrationRequest) ReflectionTestUtils.getField(run, "properties");
        source.getSourcePrimaryKeys().set(0, "other");
        mapping.getRegexColumnMapper().getFirst().setToValue("changed");
        request.getTarget().getCustomDdlMap().clear();
        request.getSource().clear();
        assertNotNull(captured);
        assertEquals(List.of("id"), captured.getSource().getFirst().getSourcePrimaryKeys());
        assertEquals("target", captured.getSource().getFirst().getConfigMap().get("records").getRegexColumnMapper().getFirst().getToValue());
        assertEquals(1, captured.getTarget().getCustomDdlMap().size());
    }

    @Test void trustedCheckpointCallbackSurvivesSnapshotButItsMapIsNotShared() {
        var request = new MigrationRequest();
        var callback = new CursorRunBinding("a".repeat(64), null, checkpoint -> true);
        request.getTarget().setCursorRunBindings(new HashMap<>(Map.of("records", callback)));
        var snapshot = request.snapshot();
        request.getTarget().getCursorRunBindings().clear();
        assertSame(callback, snapshot.getTarget().getCursorRunBindings().get("records"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getTarget().getCursorRunBindings().clear());
    }
}
