package cn.iocoder.yudao.module.dataforge.service.databasetype;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseSimpleRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo.DatabaseTypeRespVO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CursorCapabilityWireContractTest {
    @Test
    void bootMapperRoundTripsTableScopedTypeDecisionsAndOldRequestsKeepRejectingLosses() {
        var previous = JsonUtils.getObjectMapper();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, YudaoJacksonAutoConfiguration.class)).run(context -> {
                assertNull(context.getStartupFailure());
                var mapper = context.getBean(ObjectMapper.class);
                var decision = new cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyMappingAcknowledgement(
                        cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLITE3, "main", "copy", "value",
                        "a".repeat(64), java.util.Set.of("DECLARED_TYPE_AFFINITY_NOT_STRICT"));
                var request = new cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest();
                request.setTypeMappingAcknowledgements(List.of(decision));
                byte[] bytes = mapper.writeValueAsBytes(request);
                var json = mapper.readTree(bytes).get("typeMappingAcknowledgements").get(0);
                assertEquals("SQLITE3", json.get("targetProduct").asString());
                assertEquals("main", json.get("targetSchema").asString());
                assertEquals("copy", json.get("targetTable").asString());
                assertEquals("value", json.get("column").asString());
                assertEquals("a".repeat(64), json.get("mappingFingerprint").asString());
                assertTrue(json.get("lossCodes").isArray());
                assertEquals(List.of(decision), mapper.readValue(bytes,
                        cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest.class).snapshot().getTypeMappingAcknowledgements());
                assertTrue(mapper.readValue("{}", cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest.class)
                        .snapshot().getTypeMappingAcknowledgements().isEmpty());
            });
        } finally {
            JsonUtils.init(previous);
        }
    }

    @Test
    void bootMapperKeepsDirectionsAsStringArraysAndLargeIdsAsDecimalStrings() {
        var previous = JsonUtils.getObjectMapper();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, YudaoJacksonAutoConfiguration.class)).run(context -> {
                assertNull(context.getStartupFailure());
                var mapper = context.getBean(ObjectMapper.class);
                var source = new DatabaseSimpleRespVO();
                source.setId(9007199254740993L);
                source.setProductId("GBASE8A");
                source.setCursorTargets(List.of("POSTGRESQL"));
                var type = new DatabaseTypeRespVO();
                type.setId(source.getId());
                type.setProductId("GBASE8A");
                type.setCursorTargets(source.getCursorTargets());
                type.setCapabilities(Map.of("WRITE_UPSERT",
                        new DatabaseTypeRespVO.Capability("UNSUPPORTED", "TARGET_UPSERT_UNAVAILABLE")));
                for (var response : List.of(source, type)) {
                    var json = mapper.readTree(mapper.writeValueAsBytes(response));
                    assertTrue(json.get("id").isString());
                    assertEquals("9007199254740993", json.get("id").asString());
                    assertEquals("GBASE8A", json.get("productId").asString());
                    assertTrue(json.get("cursorTargets").isArray());
                    assertEquals(List.of("POSTGRESQL"), mapper.convertValue(json.get("cursorTargets"), List.class));
                }
                var capability = mapper.readTree(mapper.writeValueAsBytes(type)).get("capabilities").get("WRITE_UPSERT");
                assertEquals("UNSUPPORTED", capability.get("status").asString());
                assertEquals("TARGET_UPSERT_UNAVAILABLE", capability.get("reason").asString());
                for (var response : List.of(new DatabaseSimpleRespVO(), new DatabaseTypeRespVO())) {
                    var directions = mapper.readTree(mapper.writeValueAsBytes(response)).get("cursorTargets");
                    assertNotNull(directions);
                    assertTrue(directions.isArray());
                    assertEquals(0, directions.size());
                }
            });
        } finally {
            JsonUtils.init(previous);
        }
    }
}
