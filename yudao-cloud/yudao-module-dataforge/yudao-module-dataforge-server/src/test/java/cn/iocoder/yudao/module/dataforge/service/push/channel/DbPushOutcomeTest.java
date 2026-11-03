package cn.iocoder.yudao.module.dataforge.service.push.channel;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DbPushOutcomeTest {
    @org.junit.jupiter.api.Test
    void legacyIncrementNeverFallsBackToUnboundedCopy() {
        var channel = new DbPushChannel();
        var resolver = mock(AccessDatabaseResolver.class);
        ReflectionTestUtils.setField(channel, "databaseResolver", resolver);
        var task = new DataPushTaskDO(); task.setPushMode(2);
        assertThrows(IllegalArgumentException.class, () -> channel.syncFromSource(null, task,
                "{}", null, null, new StringBuilder(), null));
        task.setPushMode(1);
        assertThrows(IllegalArgumentException.class, () -> channel.syncFromSource(null, task,
                "{}", "1", "2", new StringBuilder(), null));
        verifyNoInteractions(resolver);
    }
    @ParameterizedTest
    @CsvSource({"CONFIRMED,true", "CONFIRMED,false", "UNCONFIRMED,true", "UNCONFIRMED,false", "RUNNING,true", "MISSING,true"})
    void onlyConfirmedSuccessCanReturnRowsAndAdvanceTheCaller(String termination, boolean successful) throws Exception {
        var source = new AccessDatabaseResolver.ConnectionInfo();
        source.setProductType(ProductTypeEnum.POSTGRESQL); source.setSchema("public");
        source.setJdbcUrl("jdbc:postgresql://invalid.test/fixture");
        var resolver = mock(AccessDatabaseResolver.class); when(resolver.resolve(eq(20L), any())).thenReturn(source);
        var channel = new DbPushChannel(); ReflectionTestUtils.setField(channel, "databaseResolver", resolver);
        var task = new DataPushTaskDO(); task.setTableName("rows");
        var result = new DbSwitchResult(); result.getIfAllSuccess().set(successful); result.getTotalRowCount().set(2);
        result.setTermination(termination.equals("MISSING") ? null : MigrationTermination.valueOf(termination));
        try (var engines = mockConstruction(MigrationService.class, (engine, context) -> when(engine.run()).thenReturn(result))) {
            org.junit.jupiter.api.function.ThrowingSupplier<Long> call = () -> channel.syncFromSource(source, task,
                    "{\"db\":{\"databaseId\":20}}", null, null, new StringBuilder(), null);
            if (!termination.equals("CONFIRMED")) {
                var failure = assertThrows(UnconfirmedWriteException.class, call::get);
                assertNull(failure.getCause());
            } else if (successful) {
                assertEquals(2L, assertDoesNotThrow(call));
            } else {
                assertThrows(IllegalStateException.class, call::get);
            }
            assertEquals(1, engines.constructed().size()); verify(engines.constructed().getFirst(), times(1)).run();
        }
    }
}
