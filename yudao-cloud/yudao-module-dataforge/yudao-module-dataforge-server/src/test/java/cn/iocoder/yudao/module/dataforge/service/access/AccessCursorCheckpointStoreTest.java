package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorCheckpoint;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.*;
import cn.iocoder.yudao.module.dataforge.service.execution.RunLease;
import org.junit.jupiter.api.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccessCursorCheckpointStoreTest {
    private final DataAccessCheckpointMapper checkpoints=mock(DataAccessCheckpointMapper.class);
    private final DataAccessIncreaseLogMapper legacy=mock(DataAccessIncreaseLogMapper.class);
    private final AccessRunCoordinator coordinator=mock(AccessRunCoordinator.class);
    private final DbSwitchRuntime runtime=mock(DbSwitchRuntime.class);
    private final ConfiguredSourceGenerations generations=mock(ConfiguredSourceGenerations.class);
    private final ConfiguredJdbcDrivers drivers=mock(ConfiguredJdbcDrivers.class);
    private final ConfiguredJdbcDrivers.Binding driver=mock(ConfiguredJdbcDrivers.Binding.class);
    private final MetaDataByJdbcServiceImpl metadata=mock(MetaDataByJdbcServiceImpl.class);
    private final DataAccessDO access=new DataAccessDO();
    private final DataAccessTaskDO run=new DataAccessTaskDO();
    private final SourceDataSourceProperties source=new SourceDataSourceProperties();
    private final TargetDataSourceProperties target=new TargetDataSourceProperties();
    private final AccessDatabaseResolver.ConnectionInfo sourceConnection=new AccessDatabaseResolver.ConnectionInfo();
    private final AccessDatabaseResolver.ConnectionInfo targetConnection=new AccessDatabaseResolver.ConnectionInfo();
    private final RunLease lease=new RunLease(100L,"fixture-token", LocalDateTime.now().plusMinutes(1));
    private AccessCursorCheckpointStore store;
    @BeforeEach void setup(){
        store=new AccessCursorCheckpointStore(checkpoints,legacy,coordinator,runtime,generations);
        access.setId(10L);run.setId(100L);run.setConfigJson("{\"version\":1}");
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);source.setSourceSchema("public");
        source.setUrl("fixture-endpoint");source.setUsername("fixture-principal");source.setIncreaseColumnName("stamp");
        source.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL);target.setChangeDataSync(true);target.setChangeDataSyncType("INCREASE_COLUMN");
        sourceConnection.setDatabaseId(20L);sourceConnection.setConfigurationRevision("source-revision");
        targetConnection.setDatabaseId(30L);targetConnection.setConfigurationRevision("target-revision");
        when(runtime.drivers()).thenReturn(drivers);when(drivers.find(ProductTypeEnum.POSTGRESQL)).thenReturn(Optional.of(driver));
        when(runtime.products()).thenReturn(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        when(driver.profileId()).thenReturn("fixture-pg");when(driver.fingerprint()).thenReturn("d".repeat(64));
        when(generations.find(ProductTypeEnum.POSTGRESQL,"fixture-endpoint","fixture-principal")).thenReturn(Optional.of(
                new SourceGenerationPin("control","11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222")));
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        var table=new TableDescription();table.setTableName("rows");
        when(metadata.queryTableList(anyString(),anyString(),isNull(),anyString())).thenReturn(List.of(table));
    }
    private Map<String,cn.iocoder.yudao.framework.dbswitch.data.cursor.CursorRunBinding> prepare(){
        return store.prepare(access,run,lease,source,target,sourceConnection,targetConnection);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ProductTypeEnum.class,names={"DORIS","STARROCKS"})
    void visibleStreamLoadReceiptSurvivesOwnedPersistenceAndResume(ProductTypeEnum product) {
        source.setSourceProductType(product);
        target.setTargetProductType(product);
        when(drivers.find(product)).thenReturn(Optional.of(driver));
        when(runtime.jdbcMetadata(product)).thenReturn(metadata);
        when(generations.find(product,"fixture-endpoint","fixture-principal")).thenReturn(Optional.of(
                new SourceGenerationPin("control","11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222")));
        when(coordinator.writeOwned(eq(lease),any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run(); return true;
        });
        var persisted=new java.util.concurrent.atomic.AtomicReference<DataAccessCheckpointDO>();
        when(checkpoints.insert(any(DataAccessCheckpointDO.class))).thenAnswer(invocation -> {
            DataAccessCheckpointDO row=invocation.getArgument(0);
            row.setId(71L);persisted.set(row);return 1;
        });
        var binding=prepare().get("rows");
        var position=new cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor(1,List.of(
                cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue.of(java.sql.Types.BIGINT,"int8",9L)));
        var checkpoint=new CursorCheckpoint(2,binding.fingerprint(),"b".repeat(64),"c".repeat(64),
                position,position,1,false,null,
                cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE,
                product,product);
        assertTrue(binding.store().compareAndSet(checkpoint));
        DataAccessCheckpointDO row=persisted.get();
        assertNotNull(row);assertEquals(100L,row.getOwnerRunId());
        assertEquals(checkpoint,AccessCursorCheckpointStore.decode(row.getCheckpointJson()));
        assertFalse(row.getCheckpointJson().contains("JDBC_COMMIT_RETURNED"));
        when(checkpoints.current(10L,"public","rows")).thenReturn(row);
        var resumed=prepare().get("rows");assertEquals(checkpoint,resumed.previous());
        when(checkpoints.compareAndSet(eq(71L),eq(10L),eq("public"),eq("rows"),eq(0L),eq(100L),anyString())).thenReturn(1);
        assertTrue(resumed.store().compareAndSet(checkpoint));
        verify(checkpoints).compareAndSet(71L,10L,"public","rows",0L,100L,JsonUtils.toJsonString(checkpoint));
        when(coordinator.writeOwned(eq(lease),any())).thenReturn(false);
        assertFalse(resumed.store().compareAndSet(checkpoint));
        verify(checkpoints,times(1)).compareAndSet(anyLong(),anyLong(),anyString(),anyString(),anyLong(),anyLong(),anyString());
    }
    @Test void persistedStreamLoadReceiptCannotBeReboundToAnUnrelatedProduct() {
        var position=new cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor(1,List.of(
                cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue.of(java.sql.Types.BIGINT,"int8",9L)));
        var checkpoint=new CursorCheckpoint(2,"a".repeat(64),"b".repeat(64),"c".repeat(64),
                position,position,1,false,null,
                cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.OLAP_STREAM_LOAD_VISIBLE,
                ProductTypeEnum.POSTGRESQL,ProductTypeEnum.DORIS);
        String json=JsonUtils.toJsonString(checkpoint);
        assertThrows(IllegalArgumentException.class,()->AccessCursorCheckpointStore.decode(json.replace("DORIS","MYSQL")));
        assertThrows(IllegalArgumentException.class,()->AccessCursorCheckpointStore.decode(json.replace("OLAP_STREAM_LOAD_VISIBLE","NONE")));
        verifyNoInteractions(checkpoints,coordinator);
    }
    @Test void typedLiteralSelectionKeepsCommaGlobAndBoundaryWhitespaceInCheckpointIdentity() {
        for (String name : List.of("a,b", "report?", "a*b", " leading", "trailing\u3000")) {
            var literal = new TableDescription(); literal.setTableName(name);
            var decoy = new TableDescription(); decoy.setTableName("report1");
            when(metadata.queryTableList(anyString(),anyString(),isNull(),anyString())).thenReturn(List.of(literal, decoy));
            source.setIncludeOrExclude(1); source.setSourceTableNames(List.of(name));
            assertEquals(Set.of(name), prepare().keySet());
            verify(checkpoints).current(10L, "public", name);
        }
    }
    @Test void invalidTypedSelectionAndLegacyPatternFailBeforeDriverOrMetadataAcquisition() {
        source.setIncludeOrExclude(1); source.setSourceTableNames(List.of("duplicate", "duplicate"));
        assertThrows(IllegalArgumentException.class, this::prepare);
        source.setSourceTableNames(null); source.setSourceIncludes("report*");
        assertThrows(IllegalArgumentException.class, this::prepare);
        verifyNoInteractions(drivers, metadata, checkpoints);
    }
    @Test
    void undeclaredDirectionFailsBeforeDriverMetadataOrCheckpointAccess() {
        source.setSourceProductType(ProductTypeEnum.CLICKHOUSE);
        target.setTargetProductType(ProductTypeEnum.MYSQL);
        assertThrows(IllegalArgumentException.class, this::prepare);
        verifyNoInteractions(drivers, metadata, checkpoints);
    }
    @Test
    void gbaseSelfTargetRequiresItsPinnedDriverBeforeMetadataOrCheckpointAccess() {
        source.setSourceProductType(ProductTypeEnum.GBASE8A);
        target.setTargetProductType(ProductTypeEnum.GBASE8A);
        assertThrows(IllegalArgumentException.class, this::prepare);
        verifyNoInteractions(metadata, checkpoints);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ProductTypeEnum.class,names={"SQLSERVER2000","GBASE8A"})
    void targetGenerationIsTrustedBoundAndRotatingItRejectsPriorCheckpoint(ProductTypeEnum product) {
        source.setSourceProductType(product); target.setTargetProductType(product);
        target.setUrl("target-fixture-endpoint"); target.setUsername("target-fixture-principal");
        when(drivers.find(product)).thenReturn(Optional.of(driver)); when(runtime.jdbcMetadata(product)).thenReturn(metadata);
        var sourcePin = new SourceGenerationPin("SOURCE89",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        var targetPin = new SourceGenerationPin("TARGET89",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        when(generations.find(product,"fixture-endpoint","fixture-principal")).thenReturn(Optional.of(sourcePin));
        when(generations.find(product,"target-fixture-endpoint","target-fixture-principal")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,this::prepare); verifyNoInteractions(metadata,checkpoints);
        when(generations.find(product,"target-fixture-endpoint","target-fixture-principal")).thenReturn(Optional.of(targetPin));
        var binding = prepare().get("rows"); assertEquals(sourcePin,binding.sourceGenerationPin()); assertEquals(targetPin,binding.targetGenerationPin());
        var previous = new CursorCheckpoint(2,binding.fingerprint(),"b".repeat(64),"c".repeat(64),null,null,0,true,null,
                cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.NONE,product,product);
        var row = new DataAccessCheckpointDO(); row.setId(1L); row.setRevision(0L); row.setCheckpointJson(JsonUtils.toJsonString(previous));
        when(checkpoints.current(10L,"public","rows")).thenReturn(row); assertEquals(previous,prepare().get("rows").previous());
        when(generations.find(product,"target-fixture-endpoint","target-fixture-principal")).thenReturn(Optional.of(
                new SourceGenerationPin(targetPin.schema(),targetPin.instanceId(),UUID.randomUUID().toString())));
        assertThrows(IllegalArgumentException.class,this::prepare); verify(checkpoints,never()).insert(any(DataAccessCheckpointDO.class));
    }
    @Test void fixedDriverAndSourceGenerationAreMandatoryBeforeSourceMetadata(){
        when(drivers.find(ProductTypeEnum.POSTGRESQL)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,this::prepare);verifyNoInteractions(metadata);
        when(drivers.find(ProductTypeEnum.POSTGRESQL)).thenReturn(Optional.of(driver));
        when(generations.find(any(),anyString(),anyString())).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,this::prepare);verifyNoInteractions(metadata);
    }
    @Test void legacyStringWatermarkRequiresExplicitBootstrapAndNeverBecomesTypedPosition(){
        when(legacy.selectNewest(10L,"public","rows","stamp")).thenReturn(new DataAccessIncreaseLogDO());
        assertThrows(IllegalArgumentException.class,this::prepare);
        source.setCursorBootstrapAllowed(true);var binding=prepare().get("rows");assertNull(binding.previous());
        verify(checkpoints,never()).insert(any(DataAccessCheckpointDO.class));
    }
    @Test void revisionAndPlanChangesCannotSilentlyReuseCheckpoint(){
        var binding=prepare().get("rows");
        var row=new DataAccessCheckpointDO();row.setId(1L);row.setRevision(4L);
        row.setCheckpointJson(JsonUtils.toJsonString(new CursorCheckpoint(1,binding.fingerprint(),"b".repeat(64),"c".repeat(64),null,null,0,true,null)));
        when(checkpoints.current(10L,"public","rows")).thenReturn(row);
        assertNotNull(prepare().get("rows").previous());
        sourceConnection.setConfigurationRevision("source-revision-2");assertThrows(IllegalArgumentException.class,this::prepare);
    }
    @Test void leaseFailureCannotAdvanceOrInsertCheckpoint(){
        var binding=prepare().get("rows");when(coordinator.writeOwned(eq(lease),any())).thenReturn(false);
        assertFalse(binding.store().compareAndSet(new CursorCheckpoint(1,binding.fingerprint(),"b".repeat(64),"c".repeat(64),null,null,0,false,null)));
        verify(checkpoints,never()).insert(any(DataAccessCheckpointDO.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "POSTGRESQL,POSTGRESQL", "POSTGRESQL,MYSQL", "POSTGRESQL,MARIADB",
            "MYSQL,POSTGRESQL", "MYSQL,MYSQL", "MYSQL,MARIADB",
            "MARIADB,POSTGRESQL", "MARIADB,MYSQL", "MARIADB,MARIADB",
            "TIDB,TIDB", "TIDB,POSTGRESQL", "POSTGRESQL,TIDB", "SQLITE3,SQLITE3"})
    void provenPairUsesActualProductsDefaultDriversAndTrustedSourcePin(ProductTypeEnum product, ProductTypeEnum targetProduct){
        source.setSourceProductType(product);
        target.setTargetProductType(targetProduct);
        String schema=product==ProductTypeEnum.SQLITE3?"main":"public";
        source.setSourceSchema(schema);
        var targetDriver=targetProduct==product?driver:mock(ConfiguredJdbcDrivers.Binding.class);
        when(targetDriver.profileId()).thenReturn("target-profile");when(targetDriver.fingerprint()).thenReturn("e".repeat(64));
        when(drivers.find(product)).thenReturn(Optional.of(driver));
        when(drivers.find(targetProduct)).thenReturn(Optional.of(targetDriver));
        var pin=new SourceGenerationPin(schema,"11111111-1111-1111-1111-111111111111","22222222-2222-2222-2222-222222222222");
        when(generations.find(product,"fixture-endpoint","fixture-principal")).thenReturn(Optional.of(pin));
        when(runtime.jdbcMetadata(product)).thenReturn(metadata);
        var binding=prepare().get("rows");assertNotNull(binding);
        assertSame(pin,binding.sourceGenerationPin());
        verify(drivers,times(product==targetProduct?2:1)).find(product);verify(drivers,times(product==targetProduct?2:1)).find(targetProduct);
        verify(driver,times(product==targetProduct?2:1)).verifyUnchanged();verify(targetDriver,times(product==targetProduct?2:1)).verifyUnchanged();
        verify(generations).find(product,"fixture-endpoint","fixture-principal");
        verify(runtime).jdbcMetadata(product);
        var row=new DataAccessCheckpointDO();row.setId(1L);row.setRevision(4L);
        int version=product==ProductTypeEnum.POSTGRESQL&&targetProduct==ProductTypeEnum.POSTGRESQL?1:2;
        row.setCheckpointJson(JsonUtils.toJsonString(new CursorCheckpoint(version,binding.fingerprint(),"b".repeat(64),"c".repeat(64),
                null,null,0,true,null,cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement.NONE,product,targetProduct)));
        when(checkpoints.current(10L,schema,"rows")).thenReturn(row);assertNotNull(prepare().get("rows").previous());
        when(generations.find(product,"fixture-endpoint","fixture-principal")).thenReturn(Optional.of(
                new SourceGenerationPin(schema,"11111111-1111-1111-1111-111111111111","33333333-3333-3333-3333-333333333333")));
        assertThrows(IllegalArgumentException.class,this::prepare);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ProductTypeEnum.class,names={"POSTGRESQL","MYSQL","MARIADB","TIDB","SQLITE3"})
    void noSourceMetadataOrCheckpointWriteWithoutDeploymentPinAndDefaultDriver(ProductTypeEnum product){
        source.setSourceProductType(product);target.setTargetProductType(product);
        when(drivers.find(product)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,this::prepare);
        verifyNoInteractions(metadata,checkpoints);
        when(drivers.find(product)).thenReturn(Optional.of(driver));
        when(generations.find(product,"fixture-endpoint","fixture-principal")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,this::prepare);
        verifyNoInteractions(metadata,checkpoints);
    }
}
