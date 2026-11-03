package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessRespVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.access.config.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Can run unchanged on the previous source snapshot to demonstrate the missing literal contract. */
class AccessTypedSelectionCompatibilityTest {
    private static final List<String> NAMES = List.of("a,b", "report?", "a*b", " leading", "trailing\u3000");

    @Test void optionalVersionOneLiteralNamesSurviveCodecAndRuntimeWithoutCsv() {
        var config = assertDoesNotThrow(() -> AccessTaskConfigCodec.read(json()));
        var runtime = AccessTaskConfigMapper.toRuntime(config, 20L).snapshot();
        assertEquals(NAMES, ReflectionTestUtils.getField(runtime.getSource().getFirst(), "sourceTableNames"));
        assertEquals("", runtime.getSource().getFirst().getSourceIncludes());
        assertEquals(1, config.getSchemaVersion());
    }

    @Test void saveStoresLiteralNamesAndDetailReturnsThemWithoutSplittingOrTrimming() {
        var service = new DataAccessServiceImpl(); var databases = mock(DatabaseMapper.class);
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        when(databases.selectById(10L)).thenReturn(DatabaseDO.builder().id(10L).build());
        when(databases.selectById(20L)).thenReturn(DatabaseDO.builder().id(20L).build());
        var request = new DataAccessSaveReqVO(); request.setTaskName("literal scope");
        request.setEngineType(1); request.setTaskType(2); request.setSourceType(1); request.setAccessMode(2);
        request.setSourceDatabaseId(10L); request.setTargetDatabaseId(20L); request.setIncludeOrExclude(1);
        request.setSourceSelectedTables(NAMES);
        DataAccessDO definition = assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "buildDataAccess", request));
        var response = new DataAccessRespVO();
        ReflectionTestUtils.invokeMethod(service, "fillConfig", response, definition.getDataAccessJson());
        assertEquals(NAMES, response.getSourceSelectedTables());
        assertEquals("", definition.getDataAccessJson().getSource().getFirst().getSourceIncludes());
    }

    @Test void frozenRunKeepsLiteralNamesAndLossAcknowledgementScope() {
        var config = assertDoesNotThrow(() -> AccessTaskConfigCodec.read(json()));
        var acknowledgement = new AccessTypeMappingAcknowledgement("POSTGRESQL", "target", "ods_a,b", "note",
                "a".repeat(64), List.of("DECLARED_TYPE_AFFINITY_NOT_STRICT"));
        config.setTypeMappingAcknowledgements(List.of(acknowledgement));
        var definition = DataAccessDO.builder().id(7L).engineType(1).sourceDatabaseId(10L)
                .targetDatabaseId(20L).dataAccessJson(config).build();
        var frozen = AccessRunSnapshot.freeze(definition);
        var restored = AccessRunSnapshot.restore(frozen);
        var request = AccessTaskConfigMapper.toRuntime(restored.getDataAccessJson(), 20L).snapshot();
        assertEquals(NAMES, ReflectionTestUtils.getField(request.getSource().getFirst(), "sourceTableNames"));
        assertEquals("ods_a,b", request.getTypeMappingAcknowledgements().getFirst().targetTable());
        assertFalse(frozen.contains("password"));
        assertFalse(frozen.contains("jdbc:"));
    }

    @Test void malformedLiteralListsDoNotBypassVersionOneWhitelistOrLegacyScope() {
        for (String value : List.of("\"a,b\"", "[1]", "[true]", "[{}]", "[[]]", "[null]", "[\"\"]", "[\"same\",\"same\"]", "[\"bad\\u0000name\"]"))
            assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(
                    "{\"schemaVersion\":1,\"source\":[{\"sourceTableNames\":" + value + "}],\"target\":{}}"), value);
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json().replace(
                "\"sourceTableNames\":", "\"password\":\"forbidden\",\"sourceTableNames\":")));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json().replace(
                "\"sourceTableNames\":", "\"sourceIncludes\":\"legacy\",\"sourceTableNames\":")));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json().replace(
                "\"sourceTableNames\":", "\"sourceType\":2,\"sourceTableNames\":")));
        var legacy = AccessTaskConfigCodec.read("{\"schemaVersion\":1,\"source\":[{\"sourceIncludes\":\"normal\"}],\"target\":{}}");
        assertNull(ReflectionTestUtils.getField(AccessTaskConfigMapper.toRuntime(legacy, 20L).getSource().getFirst(), "sourceTableNames"));
        assertEquals(List.of("normal"), AccessExactTableSelection.decodeCsv(legacy.getSource().getFirst().getSourceIncludes()));
        var explicitNull = AccessTaskConfigCodec.read("{\"schemaVersion\":1,\"source\":[{\"sourceIncludes\":\"normal\",\"sourceTableNames\":null}],\"target\":{}}");
        assertNull(ReflectionTestUtils.getField(AccessTaskConfigMapper.toRuntime(explicitNull, 20L).getSource().getFirst(), "sourceTableNames"));
        assertEquals(List.of("normal"), AccessExactTableSelection.selectedNames(explicitNull.getSource().getFirst()));
    }

    private static String json() {
        return "{\"schemaVersion\":1,\"source\":[{\"sourceTableNames\":[\"a,b\",\"report?\",\"a*b\",\" leading\",\"trailing\\u3000\"]}],\"target\":{}}";
    }
}
