package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.influxdb.InfluxdbDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InfluxDifferencePreparationTest {
    @Test void lostInitialManagementResponseRetainsExactResourceAndClosesConnection()throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);when(source.getConnection()).thenReturn(connection);
        var session=mock(InfluxNativeSession.class);var table=table();var definition=definition(table);
        var provider=mock(TableRewriteBindingProvider.class);var sync=new InfluxdbDatabaseSyncImpl(source);sync.bindTableRewriteProvider(provider);
        var resource=new InfluxNativeIdentity.CacheResource("fixture","rows","owned_attempt",null,null);
        try(var tables=mockStatic(InfluxNativeTables.class);var sessions=mockStatic(InfluxNativeSession.class);var identities=mockStatic(InfluxNativeIdentity.class)) {
            tables.when(()->InfluxNativeTables.describe(eq(source),eq("iox"),eq("rows"),anyLong(),anyInt())).thenReturn(definition);
            tables.when(()->InfluxNativeTables.exactDefinition(definition,"rows")).thenReturn(table);
            tables.when(()->InfluxNativeTables.logicalResource(definition,"rows")).thenReturn("a".repeat(64));
            sessions.when(()->InfluxNativeSession.open(eq(source),eq(connection),anyLong(),anyInt())).thenReturn(session);
            sessions.when(()->InfluxNativeSession.encode(anyString())).thenCallRealMethod();
            identities.when(()->InfluxNativeIdentity.capture(eq(session),eq(table),eq(InfluxNativeIdentity.ManagementProbePermission.EXCLUSIVE_TARGET_REPLACEMENT),any())).thenAnswer(call->{
                Consumer<Optional<InfluxNativeIdentity.CacheResource>> observer=call.getArgument(3);observer.accept(Optional.of(resource));throw new UnconfirmedWriteException();
            });
            assertThrows(UnconfirmedWriteException.class,()->sync.prepare("iox","rows",table.fields(),table.keys()));
        }
        verify(connection).close();verify(provider).authorizeManagementProbe("iox","rows","a".repeat(64));verify(provider,never()).resolve(any(),any(),any(),any(),any());
        var retained=sync.recoveryResources();assertEquals(1,retained.size());assertEquals("fixture",retained.getFirst().schema());
        assertEquals("last-cache/rows/owned_attempt",retained.getFirst().name());assertNull(retained.getFirst().expectedPhysicalIdentity());
        assertEquals(NativeRecoveryResource.Role.STAGING,retained.getFirst().role());assertThrows(IllegalStateException.class,sync::wholeTableDifference);
    }
    @Test void functionalProviderAloneCannotAuthorizeManagementProbe()throws Exception {
        var source=mock(DataSource.class);var table=table();var definition=definition(table);var sync=new InfluxdbDatabaseSyncImpl(source);
        sync.bindTableRewriteProvider((s,t,logical,identity,structure)->{throw new AssertionError("Identity admission must not precede management permission");});
        try(var tables=mockStatic(InfluxNativeTables.class)) {
            tables.when(()->InfluxNativeTables.describe(eq(source),eq("iox"),eq("rows"),anyLong(),anyInt())).thenReturn(definition);
            tables.when(()->InfluxNativeTables.exactDefinition(definition,"rows")).thenReturn(table);
            tables.when(()->InfluxNativeTables.logicalResource(definition,"rows")).thenReturn("a".repeat(64));
            assertThrows(UnsupportedOperationException.class,()->sync.prepare("iox","rows",table.fields(),table.keys()));
        }
        verify(source,never()).getConnection();assertTrue(sync.recoveryResources().isEmpty());
    }
    private static InfluxNativeCatalog.Table table(){return new InfluxNativeCatalog.Table("rows",List.of(new InfluxNativeCatalog.Column("host",InfluxNativeCatalog.Kind.TAG),new InfluxNativeCatalog.Column("time",InfluxNativeCatalog.Kind.TIME),new InfluxNativeCatalog.Column("value",InfluxNativeCatalog.Kind.INT64)));}
    private static NativeTableDefinition definition(InfluxNativeCatalog.Table table){return new NativeTableDefinition(ProductTypeEnum.INFLUXDB3,"fixture","{}",table.fields());}
}
