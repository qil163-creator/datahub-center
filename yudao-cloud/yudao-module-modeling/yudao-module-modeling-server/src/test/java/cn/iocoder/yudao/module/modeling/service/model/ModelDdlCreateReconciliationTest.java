package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 新建表对账必须核验冻结布局，且核验期间目标漂移不得进入成功回调。 */
class ModelDdlCreateReconciliationTest {
    @Test void matchingColumnsWithDifferentNativeLayoutRemainUnconfirmed() throws Exception {
        check(false, false);
    }

    @Test void matchingFrozenLayoutAndStableTargetCanBeConfirmed() throws Exception {
        check(true, false);
    }

    @Test void targetReplacementDuringDefinitionComparisonCannotReachCompletion() throws Exception {
        check(true, true);
    }

    private void check(boolean layoutMatches, boolean changed) throws Exception {
        var runtime = mock(DbSwitchRuntime.class);
        var products = mock(DbSwitchProductRegistry.class);
        var database = mock(AbstractDatabase.class);
        var connection = mock(Connection.class);
        var info = new ModelingDatabaseResolver.ConnectionInfo();
        info.setProductType(ProductTypeEnum.DORIS); info.setSchema("dw");
        info.setJdbcUrl("jdbc:fixture"); info.setUsername("fixture"); info.setPassword("");
        when(runtime.products()).thenReturn(products);
        when(products.metadata(ProductTypeEnum.DORIS)).thenReturn(database);
        when(runtime.openConnection(ProductTypeEnum.DORIS, null, "jdbc:fixture", "fixture", "")).thenReturn(connection);
        var executor = new ModelDdlExecutor();
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        var metadata = new TableSchemaMetadata(List.of(), List.of(), MetadataProvenance.unknown(),
                MetadataValue.value(true), MetadataValue.absent());
        var observed = new SchemaObservation("dw", "orders", "scope", "generation", false, metadata);
        var confirmed = new SchemaObservation("dw", "orders", "scope", changed ? "replaced" : "generation", false, metadata);
        var field = new ModelFieldItem(); field.setFieldName("id"); field.setDataType("bigint");
        String frozenCreate = "frozen CREATE from this deployment task";
        when(database.matchesModelingCreate(connection, "dw", "orders", frozenCreate, 10)).thenReturn(layoutMatches);
        var called = new AtomicBoolean();
        var accepted = new AtomicBoolean();
        try (var observations = mockStatic(SchemaChanges.class);
             var planning = mockStatic(SchemaSynchronization.class)) {
            observations.when(() -> SchemaChanges.supports(ProductTypeEnum.DORIS)).thenReturn(true);
            observations.when(() -> SchemaChanges.withGuardedObservation(eq(connection), eq(database), eq("dw"),
                    eq("orders"), eq(MetadataReadOptions.fresh()), any())).thenAnswer(call -> {
                Consumer<SchemaObservation> callback = call.getArgument(5);
                callback.accept(observed);
                return null;
            });
            observations.when(() -> SchemaChanges.observe(connection, database, "dw", "orders", MetadataReadOptions.fresh()))
                    .thenReturn(confirmed);
            planning.when(() -> SchemaSynchronization.plan(eq(connection), eq(database), eq("dw"), eq("orders"), eq("订单"), anyList()))
                    .thenReturn(new SchemaSynchronization.Plan(ProductTypeEnum.DORIS, List.of(), List.of()));
            Runnable reconcile = () -> executor.withLockedExpectedObservation(info, "orders", "订单", List.of(field),
                    frozenCreate, (table, matches) -> { called.set(true); accepted.set(matches); });
            if (changed) {
                assertThrows(IllegalStateException.class, reconcile::run);
                assertFalse(called.get());
            } else {
                reconcile.run();
                assertTrue(called.get());
                assertEquals(layoutMatches, accepted.get());
            }
        }
        verify(database).matchesModelingCreate(connection, "dw", "orders", frozenCreate, 10);
        verify(connection).close();
    }
}
