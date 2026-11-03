package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDetailDO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class AccessTypeMappingWireContractTest {
    @Test void bootPreviewContextHasOneInheritedTargetAndKeepsLiteralSelectionsAndLargeIds() {
        withMapper(mapper -> {
            var request = new AccessTypeMappingPreviewReqVO(); request.setSourceDatabaseId(1L);
            request.setTargetDatabaseId(9007199254740993L); request.setSourcePrimaryKeys(java.util.List.of("id"));
            request.setSyncDefaultVal(true); request.setPreviewTableName("a,b");
            request.setSourceSelectedTables(java.util.List.of("a,b", "report?", "a*b", " leading", "trailing\u3000"));
            byte[] bytes = mapper.writeValueAsBytes(request); var json = mapper.readTree(bytes);
            assertEquals("9007199254740993", json.get("targetDatabaseId").asString());
            assertEquals(1, new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\"targetDatabaseId\":", -1).length - 1);
            var restored = mapper.readValue(bytes, AccessTypeMappingPreviewReqVO.class);
            assertEquals(request.getSourceSelectedTables(), restored.getSourceSelectedTables());
            assertEquals(java.util.List.of("id"), restored.getSourcePrimaryKeys()); assertTrue(restored.isSyncDefaultVal());
            assertEquals(request.getTargetDatabaseId(), restored.getTargetDatabaseId());
            var old = mapper.readValue("{}", DataAccessPreviewReqVO.class);
            assertNull(old.getTargetDatabaseId()); assertTrue(old.getSourcePrimaryKeys().isEmpty()); assertFalse(old.isSyncDefaultVal());
        });
    }

    @Test void typePreviewStillRequiresTargetWhileLegacyNamePreviewKeepsItOptional() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var typed = new AccessTypeMappingPreviewReqVO(); typed.setSourceDatabaseId(1L);
            assertTrue(validator.validate(typed).stream().anyMatch(value -> value.getPropertyPath().toString().equals("targetDatabaseId")));
            var legacy = new DataAccessPreviewReqVO(); legacy.setSourceDatabaseId(1L);
            assertTrue(validator.validate(legacy).isEmpty());
        }
    }

    @Test void bootRequestRoundTripsBusinessDecisionsAndOldRequestsDefaultToAnEmptyList() {
        withMapper(mapper -> {
            var request = new DataAccessSaveReqVO(); request.setSourceDatabaseId(9007199254740993L);
            request.setTargetDatabaseId(2L); request.setTypeMappingAcknowledgements(java.util.List.of(AccessTypeMappingConfigTest.decision()));
            byte[] bytes = mapper.writeValueAsBytes(request); var json = mapper.readTree(bytes);
            assertTrue(json.get("sourceDatabaseId").isString()); assertEquals("9007199254740993", json.get("sourceDatabaseId").asString());
            var decision = json.get("typeMappingAcknowledgements").get(0);
            assertEquals("POSTGRESQL", decision.get("targetProduct").asString()); assertTrue(decision.get("lossCodes").isArray());
            assertEquals(request.getTypeMappingAcknowledgements(), mapper.readValue(bytes, DataAccessSaveReqVO.class).getTypeMappingAcknowledgements());
            assertTrue(mapper.readValue("{}", DataAccessSaveReqVO.class).getTypeMappingAcknowledgements().isEmpty());
            assertTrue(mapper.readValue("{}", DataAccessRespVO.class).getTypeMappingAcknowledgements().isEmpty());
            assertEquals(1, mapper.readValue("{}", AccessTypeMappingPreviewReqVO.class).getSourceType());
        });
    }

    @Test void bootResponseAndStoredDomainRecordsKeepStructuredReasonsAndMetadataOnlyFingerprints() {
        withMapper(mapper -> {
            var diagnostic = AccessTypeMappingDiagnostics.from(AccessTypeMappingPostgreSqlTest.plan(false),
                    "main", "orders", "migration", "ods_orders");
            var detail = DataAccessTaskDetailDO.builder().id(9007199254740993L).ifSuccess(0)
                    .typeMappingPlan(AccessTypeMappingDiagnostics.write(diagnostic)).build();
            var response = AccessTypeMappingDiagnostics.detailResponse(detail);
            var json = mapper.readTree(mapper.writeValueAsBytes(response));
            assertTrue(json.get("id").isString()); var plan = json.get("typeMappingPlan");
            assertEquals(1, plan.get("schemaVersion").asInt()); assertFalse(plan.get("accepted").asBoolean());
            assertEquals("ods_orders", plan.get("targetTable").asString());
            var column = plan.get("columns").get(0); assertEquals("LOSSY", column.get("fidelity").asString());
            assertTrue(column.get("lossCodes").isArray()); assertEquals(64, column.get("mappingFingerprint").asString().length());
            assertEquals("LOSS", column.get("issues").get(0).get("severity").asString());
            assertEquals(diagnostic, mapper.readValue(detail.getTypeMappingPlan(), AccessTypeMappingPlan.class));
            assertNull(AccessTypeMappingDiagnostics.readResponse(null));
        });
    }

    private static void withMapper(java.util.function.Consumer<ObjectMapper> assertions) {
        var previous = JsonUtils.getObjectMapper();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, YudaoJacksonAutoConfiguration.class)).run(context -> {
                assertNull(context.getStartupFailure()); assertions.accept(context.getBean(ObjectMapper.class));
            });
        } finally { JsonUtils.init(previous); }
    }
}
