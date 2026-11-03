package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeLogDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.enums.master.MasterRunStatusEnum;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterDistributeOutcomeTest {
    @org.junit.jupiter.api.Test
    void responseKeepsUnknownLargeIdsAndHidesLeaseToken() {
        var run=MasterDistributeLogDO.builder().id(9007199254740993L).distributeId(9007199254740995L)
                .runStatus(5).leaseToken("private-lease").startTime(java.time.LocalDateTime.of(2026,9,27,12,0)).build();
        var vo=cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(run,
                cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeLogRespVO.class);
        var module=new tools.jackson.databind.module.SimpleModule();
        module.addSerializer(Long.class,cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer.INSTANCE);
        module.addSerializer(java.time.LocalDateTime.class,cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer.INSTANCE);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().addModule(module).build();
        String json=mapper.writeValueAsString(vo);
        assertTrue(json.contains("\"id\":\"9007199254740993\""));assertTrue(json.contains("\"runStatus\":5"));
        assertTrue(mapper.readTree(json).get("startTime").isIntegralNumber());assertFalse(json.contains("lease"));
    }

    @org.junit.jupiter.api.Test
    void unconfirmedWriteMustPersistUnknownInsteadOfRetryableFailure() {
        var tasks = mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper.class);
        var coordinator = mock(MasterDistributeRunCoordinator.class);
        when(coordinator.begin(any())).thenReturn(true);
        var models = mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper.class);
        var task = cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeDO.builder()
                .id(10L).masterModelId(20L).distributeType(1).distributeJson("{\"db\":{\"databaseId\":30}}").build();
        when(tasks.selectById(10L)).thenReturn(task);
        var model = new MasterModelDO(); model.setId(20L); model.setStatus(1); model.setDatabaseId(40L); model.setTableName("rows");
        when(models.selectById(20L)).thenReturn(model);
        var connection = new MasterDatabaseResolver.ConnectionInfo(); connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setSchema("public"); connection.setJdbcUrl("jdbc:postgresql://invalid.test/fixture");
        var resolver = mock(MasterDatabaseResolver.class); when(resolver.resolve(anyLong(), any())).thenReturn(connection);
        var executor = new MasterDistributeExecutor();
        ReflectionTestUtils.setField(executor, "distributeMapper", tasks); ReflectionTestUtils.setField(executor, "coordinator", coordinator);
        ReflectionTestUtils.setField(executor, "modelMapper", models); ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        try (var engines = mockConstruction(MigrationService.class, (engine, context) -> when(engine.run()).thenThrow(new UnconfirmedWriteException()))) {
            var run = MasterDistributeLogDO.builder().id(100L).distributeId(10L).dataCount(0L).byteCount("0").build();
            executor.execute(run);
            verify(coordinator).finish(eq(run), eq(true), eq(5), anyString());
        }
    }

    @ParameterizedTest
    @CsvSource({"CONFIRMED,true", "CONFIRMED,false", "UNCONFIRMED,true", "UNCONFIRMED,false", "RUNNING,true", "MISSING,true"})
    void unknownTerminationNeverBecomesSuccessfulDistribution(String termination, boolean successful) throws Exception {
        var source = new MasterDatabaseResolver.ConnectionInfo();
        source.setProductType(ProductTypeEnum.POSTGRESQL); source.setSchema("public");
        source.setJdbcUrl("jdbc:postgresql://invalid.test/fixture");
        var resolver = mock(MasterDatabaseResolver.class); when(resolver.resolve(anyLong(), any())).thenReturn(source);
        var executor = new MasterDistributeExecutor(); ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        var model = new MasterModelDO(); model.setTableName("rows"); model.setDatabaseId(10L);
        var config = new MasterDistributeConfigVO.DbConfig(); config.setDatabaseId(20L);
        var log = MasterDistributeLogDO.builder().runStatus(MasterRunStatusEnum.RUNNING.getStatus()).build();
        var result = new DbSwitchResult(); result.getIfAllSuccess().set(successful); result.getTotalRowCount().set(2);
        result.setTermination(termination.equals("MISSING") ? null : MigrationTermination.valueOf(termination));
        try (var engines = mockConstruction(MigrationService.class, (engine, context) -> when(engine.run()).thenReturn(result))) {
            org.junit.jupiter.api.function.Executable call = () -> ReflectionTestUtils.invokeMethod(executor, "executeDb", model, config, log);
            if (!termination.equals("CONFIRMED")) {
                var failure = assertThrows(UnconfirmedWriteException.class, call); assertNull(failure.getCause());
                assertNotEquals(MasterRunStatusEnum.SUCCESS.getStatus(), log.getRunStatus());
            } else {
                assertDoesNotThrow(call);
                assertEquals(successful ? MasterRunStatusEnum.SUCCESS.getStatus() : MasterRunStatusEnum.UNKNOWN.getStatus(), log.getRunStatus());
                assertEquals(2L, log.getDataCount());
            }
            assertEquals(1, engines.constructed().size()); verify(engines.constructed().getFirst(), times(1)).run();
        }
    }
}
