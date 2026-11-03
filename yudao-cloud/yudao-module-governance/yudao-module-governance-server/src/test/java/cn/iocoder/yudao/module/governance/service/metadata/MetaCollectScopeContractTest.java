package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectSources;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file.MetaFileUpdateBizReqVO;
import org.junit.jupiter.api.Test;
import jakarta.validation.Validation;
import static org.junit.jupiter.api.Assertions.*;

class MetaCollectScopeContractTest {
    @Test void emptyOrMalformedCustomScopeNeverBecomesFullScan() {
        for(String value : new String[]{null,"", "[]", "{}", "[{}]", "[{\"db\":\"x\",\"tables\":\"*\"}]", "[{\"db\":\"x\",\"tables\":[]},{\"db\":\"x\",\"tables\":[]}]"}) {
            var task=MetaCollectTaskDO.builder().scopeType(2).scopeConfig(value).build();
            assertThrows(ServiceException.class,()->MetaCollectSources.parseScope(task),String.valueOf(value));
        }
        assertNull(MetaCollectSources.parseScope(MetaCollectTaskDO.builder().scopeType(1).build()));
    }
    @Test void exactIdentifiersRemainLiteralAndBlacklistIsBounded() {
        var task=MetaCollectTaskDO.builder().scopeType(2).scopeConfig("[{\"db\":\"schema name\",\"tables\":[\"order.detail\",\"100%中文\"]}]").build();
        assertTrue(MetaCollectSources.parseScope(task).get("schema name").contains("order.detail"));
        task.setBlacklist("[123]"); assertThrows(ServiceException.class,()->MetaCollectSources.validateScopeSyntax(task));
        task.setBlacklist("[\"tmp_*\"]"); assertDoesNotThrow(()->MetaCollectSources.validateScopeSyntax(task));
    }
    @Test void browserContractsRequireKnownStrategyAndBusinessRevision() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            var req=new MetaCollectTaskSaveReqVO();req.setName("测试");req.setDatasourceId(1L);req.setScopeType(1);
            assertTrue(validator.validate(req).isEmpty()); req.setExecuteStrategy("UNKNOWN");assertFalse(validator.validate(req).isEmpty());
            var file=new MetaFileUpdateBizReqVO();file.setId(1L); assertFalse(validator.validate(file).isEmpty());
            file.setBizRevision(0);assertTrue(validator.validate(file).isEmpty());file.setDescription("x".repeat(257));assertFalse(validator.validate(file).isEmpty());
        }
    }
    @Test void responseDtosDoNotExposeExecutionLeaseOrSecret() {
        var response=new cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectInstanceRespVO();
        String json=JsonUtils.toJsonString(response);
        assertFalse(json.contains("executionToken")); assertFalse(json.contains("password")); assertFalse(json.contains("leaseUntil"));
    }
    @Test void temporaryTableFlagUsesItsOwnValidatedBusinessContract() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaTableUpdateBizReqVO();
            request.setId(1L); request.setBizRevision(0); request.setTemporaryFlag(1); request.setSensitiveFlag(0);
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.setTemporaryFlag(2);
            assertFalse(factory.getValidator().validate(request).isEmpty());
            request.setTemporaryFlag(-1);
            assertFalse(factory.getValidator().validate(request).isEmpty());
            request.setTemporaryFlag(0); request.setStorageType("x".repeat(129));
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }
    @Test void bootJsonKeepsLargeIdentifiersAndSizesLosslessAndDatesInMilliseconds() {
        var previous=JsonUtils.getObjectMapper();
        try {
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                            org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                            cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration.class)).run(context->{
                assertNull(context.getStartupFailure()); var mapper=context.getBean(tools.jackson.databind.ObjectMapper.class);
                var req=mapper.readValue("{\"id\":\"9007199254740993\",\"bizRevision\":2}",MetaFileUpdateBizReqVO.class);
                assertEquals(9007199254740993L,req.getId());
                var file=new cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file.MetaFileRespVO();
                file.setId(req.getId()); file.setDatasourceId(7L); file.setSize(9007199254740993L);
                file.setUpdateTime(java.time.LocalDateTime.of(2026,10,16,12,0));
                var json=mapper.readTree(mapper.writeValueAsBytes(file));
                assertEquals("9007199254740993",json.get("id").asString()); assertTrue(json.get("id").isString());
                assertTrue(json.get("datasourceId").isNumber()); assertTrue(json.get("size").isString());
                assertTrue(json.get("updateTime").isNumber()); assertNull(json.get("password"));
                var instance=new cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect.MetaCollectInstanceRespVO();
                instance.setId(req.getId()); instance.setStartTime(file.getUpdateTime());
                var payload=mapper.readTree(mapper.writeValueAsBytes(instance));
                assertTrue(payload.get("id").isString()); assertTrue(payload.get("startTime").isNumber());
                assertNull(payload.get("executionToken")); assertNull(payload.get("leaseUntil"));
            });
        } finally {JsonUtils.init(previous);}
    }
}
