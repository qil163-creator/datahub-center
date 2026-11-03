package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.schema.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelSchemaObservationTest {
    @Test void nativeAndJdbcReadsCannotPublishMixedOldAndNewColumnEvidence() throws Exception {
        checkObservation("jdbc-new-shape", true);
    }

    @Test void observationTimeAloneDoesNotMakeMatchingEvidenceStale() throws Exception {
        checkObservation("jdbc-shape", false);
    }

    private void checkObservation(String confirmedHash, boolean rejects) throws Exception {
        var runtime = mock(DbSwitchRuntime.class);
        var products = mock(DbSwitchProductRegistry.class);
        var database = mock(AbstractDatabase.class);
        var connection = mock(Connection.class);
        var info = new ModelingDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.POSTGRESQL); info.setSchema("public");
        info.setJdbcUrl("jdbc:fixture"); info.setUsername("fixture"); info.setPassword("");
        when(runtime.products()).thenReturn(products);
        when(products.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(database);
        when(runtime.openConnection(ProductTypeEnum.POSTGRESQL, null, "jdbc:fixture", "fixture", "")).thenReturn(connection);
        var executor = new ModelDdlExecutor();
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        var metadata = new TableSchemaMetadata(List.of(), List.of(), MetadataProvenance.unknown(),
                MetadataValue.value(true), MetadataValue.absent());
        var nativeObservation = new SchemaObservation("public", "sample", "scope", "relation", false, metadata);
        var first = new ModelJdbcTableObserver.Observation(true, false, "jdbc-shape", Instant.EPOCH,
                "catalog", "public", "sample", List.of());
        var confirmed = new ModelJdbcTableObserver.Observation(true, false, confirmedHash, Instant.EPOCH.plusSeconds(1),
                "catalog", "public", "sample", List.of());
        try (var jdbcReader = mockStatic(ModelJdbcTableObserver.class); var nativeReader = mockStatic(PostgresSchemaChanges.class)) {
            jdbcReader.when(() -> ModelJdbcTableObserver.observe(connection, "public", "sample")).thenReturn(first, confirmed);
            nativeReader.when(() -> PostgresSchemaChanges.observe(connection, database, "public", "sample", MetadataReadOptions.fresh()))
                    .thenReturn(nativeObservation);
            if (rejects) assertThrows(IllegalStateException.class, () -> executor.observeTable(info, 1L, "sample"));
            else {
                var observed = executor.observeTable(info, 1L, "sample");
                assertEquals("jdbc-shape", observed.observedSchemaHash());
                assertEquals(nativeObservation.precondition(), observed.nativePrecondition());
            }
            jdbcReader.verify(() -> ModelJdbcTableObserver.observe(connection, "public", "sample"), times(2));
        }
        verify(connection).close();
    }
}
