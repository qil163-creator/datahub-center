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
    @Test void nativeReadsCannotPublishMixedOldAndNewColumnEvidence() throws Exception {
        checkObservation(true);
    }

    @Test void observationTimeAloneDoesNotMakeMatchingEvidenceStale() throws Exception {
        checkObservation(false);
    }

    @Test void frozenNativeEvidenceRemainsComparableWhenTheDisplayHashFormatChanges() {
        var expected = new SchemaPrecondition("public", "sample", "scope", "relation", true, false, "native-shape");
        var old = new ModelDdlExecutor.TableObservation(true, false, "jdbc-shape", Instant.EPOCH, List.of(), expected);
        var current = new ModelDdlExecutor.TableObservation(true, false, "native-shape", Instant.now(), List.of(), expected);
        assertTrue(old.sameDatabaseState(current));
        var changed = new SchemaPrecondition("public", "sample", "scope", "relation", true, false, "changed-shape");
        assertFalse(old.sameDatabaseState(new ModelDdlExecutor.TableObservation(true, false, "jdbc-shape",
                Instant.now(), List.of(), changed)));
    }

    private void checkObservation(boolean rejects) throws Exception {
        var runtime = mock(DbSwitchRuntime.class);
        var products = mock(DbSwitchProductRegistry.class);
        var database = mock(AbstractDatabase.class);
        var connection = mock(Connection.class);
        var info = new ModelingDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.POSTGRESQL); info.setSchema("public");
        info.setJdbcUrl("jdbc:fixture"); info.setUsername("fixture"); info.setPassword("");
        when(runtime.products()).thenReturn(products);
        when(products.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(database);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.POSTGRESQL);
        when(runtime.openConnection(ProductTypeEnum.POSTGRESQL, null, "jdbc:fixture", "fixture", "")).thenReturn(connection);
        var executor = new ModelDdlExecutor();
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        var metadata = new TableSchemaMetadata(List.of(), List.of(), MetadataProvenance.unknown(),
                MetadataValue.value(true), MetadataValue.absent());
        var nativeObservation = new SchemaObservation("public", "sample", "scope", "relation", false, metadata);
        var changedColumn = new ColumnDescription(); changedColumn.setFieldName("added");
        var confirmedMetadata = new TableSchemaMetadata(rejects ? List.of(changedColumn) : List.of(), List.of(),
                new MetadataProvenance(ProductTypeEnum.UNKNOWN, "", "", "UNKNOWN", Instant.now()),
                MetadataValue.value(true), MetadataValue.absent());
        var confirmed = new SchemaObservation("public", "sample", "scope", "relation", false, confirmedMetadata);
        try (var nativeReader = mockStatic(PostgresSchemaChanges.class)) {
            nativeReader.when(() -> PostgresSchemaChanges.observe(connection, database, "public", "sample", MetadataReadOptions.fresh()))
                    .thenReturn(nativeObservation, confirmed);
            if (rejects) assertThrows(IllegalStateException.class, () -> executor.observeTable(info, 1L, "sample"));
            else {
                var observed = executor.observeTable(info, 1L, "sample");
                assertEquals(nativeObservation.precondition().schemaFingerprint(), observed.observedSchemaHash());
                assertEquals(nativeObservation.precondition(), observed.nativePrecondition());
            }
            nativeReader.verify(() -> PostgresSchemaChanges.observe(connection, database, "public", "sample",
                    MetadataReadOptions.fresh()), times(2));
        }
        verify(connection).close();
    }
}
