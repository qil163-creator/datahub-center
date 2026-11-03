package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.*;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTableRewriteProperties;
import cn.iocoder.yudao.module.dataforge.service.execution.RunLease;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real platform PG transactions and component journal; native IDs are trusted provider inputs.
 * This does not execute target mutations or enable the still-unwired business dispatch. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class AccessTableRewritePostgreSqlTest {
    private Connection control;
    private String schema;
    private DriverManagerDataSource dataSource;
    private AccessTableRewriteCoordinator coordinator;
    private AccessTableRewriteProperties config;
    private DbSwitchRuntime runtime;
    private ConfiguredJdbcDrivers drivers;
    private DataAccessDO access;
    private DataAccessTaskDO run;
    private RunLease lease;
    private SourceDataSourceProperties source;
    private TargetDataSourceProperties target;
    private AccessDatabaseResolver.ConnectionInfo sourceConnection, targetConnection;
    private static final String LOGICAL="1".repeat(64),STRUCTURE="3".repeat(64);
    private static final TableRewriteIdentity OLD=new TableRewriteIdentity("influx-catalog-v1","old"),NEXT=new TableRewriteIdentity("influx-catalog-v1","next");

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url"), user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "access_table_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema); sql("SET search_path TO " + schema);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) root = root.getParent();
        assertNotNull(root);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        for (String table : List.of("dh_dataforge_access", "dh_dataforge_access_task", "dh_dataforge_etl_pipeline", "dh_dataforge_etl_record")) {
            var ddl = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"" + table + "\" \\(.*?\\n\\)\\s*;", Pattern.DOTALL).matcher(baseline);
            assertTrue(ddl.find(), table);
            sql(ddl.group().replace("\"datahub-center\".", "").replaceAll(" DEFAULT nextval\\([^\\n]*?::regclass\\)", ""));
        }
        sql(baseline.substring(baseline.indexOf("-- Migration: 0038 "), baseline.indexOf("-- Migration: 0039 ")).replace("\"datahub-center\"", schema));
        String migration = baseline.substring(baseline.indexOf("-- BEGIN DB SWITCH GROUP RECOVERY 0107"), baseline.indexOf("-- END DB SWITCH GROUP RECOVERY 0107")).replace("\"datahub-center\"", schema);
        sql(migration); sql(migration); // 重放真实历史迁移片段，再执行单租户增量。
        String commandMigration = baseline.substring(baseline.indexOf("-- BEGIN DB SWITCH GROUP COMMAND 0108"), baseline.indexOf("-- END DB SWITCH GROUP COMMAND 0108")).replace("\"datahub-center\"", schema);
        sql(commandMigration); sql(commandMigration);
        String tableMigration=baseline.substring(baseline.indexOf("-- BEGIN DB SWITCH TABLE RECOVERY 0109"), baseline.indexOf("-- END DB SWITCH TABLE RECOVERY 0109")).replace("\"datahub-center\"",schema);
        sql(tableMigration);sql(tableMigration);
        sql(Files.readString(root.resolve("sql/postgresql/migrations/0169_datahub_remove-business-tenancy.sql")).replace("datahub-center", schema));
        dataSource = new DriverManagerDataSource(url, user, "");
        config = new AccessTableRewriteProperties(); config.setEnabled(true); config.setControlSchema(schema);
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        config.setCurrentKeyId("fixture"); config.setKeyring(Map.of("fixture", Base64.getEncoder().encodeToString(key))); Arrays.fill(key, (byte) 0);
        config.setExclusiveTasks(Set.of("10", "11"));
        runtime = mock(DbSwitchRuntime.class); drivers = mock(ConfiguredJdbcDrivers.class);
        when(runtime.drivers()).thenReturn(drivers); when(runtime.options()).thenReturn(DbSwitchOptions.defaults());
        for (var product : List.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.INFLUXDB3)) {
            var driver = mock(ConfiguredJdbcDrivers.Binding.class);
            when(driver.profileId()).thenReturn("fixture-" + product.name().toLowerCase(Locale.ROOT));
            when(driver.fingerprint()).thenReturn(product == ProductTypeEnum.POSTGRESQL ? "a".repeat(64) : "b".repeat(64));
            when(drivers.find(product)).thenReturn(Optional.of(driver));
        }
        source = new SourceDataSourceProperties(); source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        target = new TargetDataSourceProperties(); target.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        source.setIncludeOrExclude(1);source.setSourceIncludes("rows");target.setTargetSchema("iox");
        target.setChangeDataSync(true); target.setChangeDataSyncType("PK_ORDER_COMPARE");
        sourceConnection = connection(20, ProductTypeEnum.POSTGRESQL); targetConnection = connection(30, ProductTypeEnum.INFLUXDB3);
        seed(10, 100, 30); use(10, 100, 30);
        coordinator = new AccessTableRewriteCoordinator(dataSource, config, runtime);
        var security=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        security.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(new cn.iocoder.yudao.framework.security.core.LoginUser().setId(99L),null,List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(security);
    }
    @AfterEach void cleanup() throws Exception {
org.springframework.security.core.context.SecurityContextHolder.clearContext();
        if (control != null) try {
            if (schema != null && schema.matches("access_table_[a-f0-9]{32}")) { sql("ROLLBACK"); sql("DROP SCHEMA " + schema + " CASCADE"); }
        } finally { control.close(); }
    }

    @Test void disabledDoesNotInspectDeploymentOrDriverAndInvalidEnabledFails(){
        config.setEnabled(false);assertNull(prepare());verifyNoInteractions(runtime);
        config.setEnabled(true);config.setKeyring(Map.of());assertThrows(IllegalArgumentException.class,this::prepare);
    }
    @Test void executionIdentityIsCheckedBeforeLogicalReservation()throws Exception {
        var handle=prepare();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class,()->handle.authorizeManagementProbe("iox","rows",LOGICAL));assertEquals(0,count(""));
    }
    @Test void frozenMappedTableSetRejectsUnexpectedNativeRoute()throws Exception {
        var handle=prepare();assertThrows(RuntimeException.class,()->handle.authorizeManagementProbe("iox","other",LOGICAL));
        assertThrows(RuntimeException.class,()->handle.authorizeManagementProbe("other","rows",LOGICAL));assertEquals(0,count(""));
    }
    @Test void emptyCompletedRunReleasesOnlyAfterActualExitAndRetainsNativePin()throws Exception {
        var handle=prepare();var binding=bind(handle,OLD);binding.checkOwnership();assertFalse(handle.finished(false));assertFalse(handle.hasConfirmedExecutorDrain());
        assertTrue(handle.finished(true));assertTrue(handle.hasConfirmedExecutorDrain());assertEquals(1,count("WHERE NOT occupied AND executor_drained"));
        assertEquals("old",scalar("SELECT identity_document FROM dh_dataforge_access_table_pin"));assertResolved();
    }
    @Test void unconfirmedManagementProbeRetainsOccupiedManualPinEvenAfterDrain()throws Exception {
        var handle=prepare();handle.authorizeManagementProbe("iox","rows",LOGICAL);assertFalse(handle.finished(true));assertTrue(handle.hasConfirmedExecutorDrain());
        assertEquals(1,count("WHERE occupied AND executor_drained AND manual_required"));assertThrows(RuntimeException.class,this::assertResolved);
        var status=status();assertTrue(status.applicable());assertFalse(status.ready());assertTrue(status.message().contains("原生"));
    }
    @Test void pendingPreparedMaterialRetainsDrainAndAdmitsExplicitRecoveryOnSameTransaction()throws Exception {
        var handle=prepare();var binding=bind(handle,OLD);var record=binding.store().prepare(key(binding),lease.token(),material(binding));
        assertFalse(handle.finished(true));assertTrue(handle.hasConfirmedExecutorDrain());assertThrows(RuntimeException.class,binding::checkOwnership);
        review();assertTrue(status().ready());var recovery=claim("recovery_owner_0002");var tables=recovery.pendingTables();assertEquals(1,tables.size());
        assertEquals(record.material().hash(),tables.getFirst().record().material().hash());assertEquals("recovery_owner_0002",tables.getFirst().record().ownerToken());
        assertThrows(RuntimeException.class,this::assertResolved);assertFalse(recovery.finished(true));assertTrue(recovery.hasConfirmedExecutorDrain());
    }
    @Test void createUnknownNeverBecomesReadyAndCannotBeClaimedByName()throws Exception {
        var handle=prepare();var binding=bind(handle,OLD);var record=binding.store().prepare(key(binding),lease.token(),material(binding));
        for(var phase:List.of(TableRewriteRecord.Phase.DROP_INTENT,TableRewriteRecord.Phase.DROP_CONFIRMED,TableRewriteRecord.Phase.CREATE_INTENT,TableRewriteRecord.Phase.CREATE_UNKNOWN))
            record=binding.store().transition(record.key(),lease.token(),record.revision(),record.phase(),phase,null).orElseThrow();
        assertFalse(handle.finished(true));review();assertFalse(status().ready());assertTrue(status().message().contains("不能按同名表"));
        assertThrows(RuntimeException.class,()->claim("recovery_owner_0002"));assertEquals(1,count("WHERE occupied AND manual_required"));
    }
    @Test void recoveryRechecksExecutionIdentityBeforeOwnershipTransfer()throws Exception {
        var handle=prepare();var binding=bind(handle,OLD);binding.store().prepare(key(binding),lease.token(),material(binding));
        assertFalse(handle.finished(true));review();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class,()->coordinator.prepareRecovery(access,run,"recovery_owner_0002",source,target,sourceConnection,targetConnection));
        assertEquals(lease.token(),scalar("SELECT owner_token FROM dh_dataforge_access_table_pin"));
        assertEquals(1,count("WHERE occupied AND executor_drained"));
    }
    @Test void replayedMigrationHasChineseCommentsForEveryPublishedColumn()throws Exception {
        try(var s=control.prepareStatement("SELECT c.relname,a.attname,col_description(c.oid,a.attnum) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid WHERE n.nspname=? AND c.relname IN ('dh_dataforge_access_table_pin','dh_dataforge_access_table_recovery','dh_dataforge_access_table_page') AND a.attnum>0 AND NOT a.attisdropped")){
            s.setString(1,schema);int columns=0;
            try(var r=s.executeQuery()){while(r.next()){columns++;String comment=r.getString(3);assertNotNull(comment,r.getString(1)+'.'+r.getString(2));assertTrue(comment.codePoints().anyMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN));}}
            assertEquals(47,columns);
        }
    }
    @Test void appliedNativeGenerationAdvancesPinAndRejectsAnUnrelatedRebuild()throws Exception {
        var handle=prepare();var binding=bind(handle,OLD);applied(binding);assertTrue(handle.finished(true));
        assertEquals("next",scalar("SELECT identity_document FROM dh_dataforge_access_table_pin"));
        var later=prepare();later.authorizeManagementProbe("iox","rows",LOGICAL);
        assertThrows(RuntimeException.class,()->later.resolve("iox","rows",LOGICAL,new TableRewriteIdentity("influx-catalog-v1","foreign"),STRUCTURE));
        assertEquals("next",scalar("SELECT identity_document FROM dh_dataforge_access_table_pin"));
    }
    @Test void mutableOwnerCannotBeTamperedToHideAnOccupiedPin()throws Exception {
        var handle=prepare();bind(handle,OLD);sql("UPDATE dh_dataforge_access_table_pin SET owner_run_id=101");
        assertThrows(RuntimeException.class,this::assertResolved);assertFalse(status().ready());assertTrue(status().applicable());assertFalse(handle.finished(true));
    }
    @Test void journalCannotEscapeTheRegisteredLogicalScope()throws Exception {
        var handle=prepare();var binding=bind(handle,OLD);binding.store().prepare(key(binding),lease.token(),material(binding));
        var failure=assertThrows(SQLException.class,()->sql("UPDATE dh_dataforge_access_table_recovery SET logical_resource=repeat('f',64)"));assertEquals("23503",failure.getSQLState());
        assertFalse(handle.finished(true));assertEquals(LOGICAL,scalar("SELECT logical_resource FROM dh_dataforge_access_table_recovery"));
    }
    @Test void fakeAppliedStateCannotReleaseARealPendingMaterial()throws Exception {
        var handle=prepare();var binding=bind(handle,OLD);binding.store().prepare(key(binding),lease.token(),material(binding));sql("UPDATE dh_dataforge_access_table_recovery SET phase='APPLIED'");
        assertFalse(handle.finished(true));assertEquals(1,count("WHERE occupied AND executor_drained"));assertThrows(RuntimeException.class,this::assertResolved);
    }
    @Test void anotherTaskCannotOccupyTheSameLogicalTable()throws Exception {
        var handle=prepare();bind(handle,OLD);seed(11,101,30);use(11,101,30);
        var other=prepare();assertThrows(RuntimeException.class,()->other.authorizeManagementProbe("iox","rows",LOGICAL));assertEquals(1,count("WHERE occupied"));
    }
    @Test void missingAuthenticatedPrincipalCannotAdmit()throws Exception {
        seed(11,101,30);use(11,101,30);org.springframework.security.core.context.SecurityContextHolder.clearContext();assertThrows(IllegalArgumentException.class,this::prepare);assertEquals(0,count(""));
    }
    @Test void stopAndExpiredLeaseCannotIssueNewBinding()throws Exception {
        var handle=prepare();var binding=bind(handle,OLD);sql("UPDATE dh_dataforge_access_task SET lease_until=clock_timestamp()-interval '1 second',stop_requested=true WHERE id=100");
        assertThrows(RuntimeException.class,binding::checkOwnership);assertFalse(handle.finished(false));assertEquals(1,count("WHERE occupied AND NOT executor_drained"));
    }
    private AccessTableRewriteCoordinator.Handle prepare(){return coordinator.prepare(access,run,lease,source,target,sourceConnection,targetConnection);}
    private TableRewriteBinding bind(AccessTableRewriteCoordinator.Handle handle,TableRewriteIdentity identity){handle.authorizeManagementProbe("iox","rows",LOGICAL);return handle.resolve("iox","rows",LOGICAL,identity,STRUCTURE);}
    private TableRewriteKey key(TableRewriteBinding binding){return new TableRewriteKey(binding.scope(),LOGICAL,"4".repeat(64));}
    private TableRewriteMaterial material(TableRewriteBinding binding){return TableRewriteMaterial.create(binding.fingerprint(),"iox","rows",OLD,
            new cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition(ProductTypeEnum.INFLUXDB3,"platform-test","{}",List.of("time","value")),List.of("time"),
            Collections.singletonList(new Object[]{java.time.Instant.EPOCH,1L}),Collections.singletonList(new Object[]{java.time.Instant.EPOCH,2L}),binding.limits());}
    private void applied(TableRewriteBinding binding){var record=binding.store().prepare(key(binding),lease.token(),material(binding));
        for(var phase:List.of(TableRewriteRecord.Phase.DROP_INTENT,TableRewriteRecord.Phase.DROP_CONFIRMED,TableRewriteRecord.Phase.CREATE_INTENT,TableRewriteRecord.Phase.CREATE_CONFIRMED,TableRewriteRecord.Phase.RESTORE_INTENT,TableRewriteRecord.Phase.APPLIED))
            record=binding.store().transition(record.key(),lease.token(),record.revision(),record.phase(),phase,TableRewriteRecord.requiresNewIdentity(phase)?NEXT:null).orElseThrow();
    }
    private void review()throws Exception{sql("UPDATE dh_dataforge_access_task SET run_status=5,review_required=true,execution_token=null,lease_until=null WHERE id=100");}
    private AccessTableRewriteCoordinator.RecoveryHandle claim(String token)throws Exception {
        var admission=coordinator.prepareRecovery(access,run,token,source,target,sourceConnection,targetConnection);review();control.setAutoCommit(false);
        try {try(var s=control.prepareStatement("UPDATE dh_dataforge_access_task SET reconcile_token=?,reconcile_lease_until=clock_timestamp()+interval '5 minutes',stop_requested=false WHERE id=100")){s.setString(1,token);s.executeUpdate();}
            var result=coordinator.admitRecovery(control,admission);control.commit();return result;
        }catch(Exception failure){control.rollback();throw failure;}finally{control.setAutoCommit(true);}
    }
    private void assertResolved()throws Exception {control.setAutoCommit(false);try{coordinator.assertResolved(control,10,100);}finally{control.rollback();control.setAutoCommit(true);}}
    private AccessGroupRewriteCoordinator.RecoveryStatus status()throws Exception {control.setReadOnly(true);control.setAutoCommit(false);try{return coordinator.recoveryStatus(control,10,100);}finally{control.rollback();control.setAutoCommit(true);control.setReadOnly(false);}}
    private AccessDatabaseResolver.ConnectionInfo connection(long id, ProductTypeEnum product) {
        var info = new AccessDatabaseResolver.ConnectionInfo(); info.setDatabaseId(id); info.setProductType(product); info.setConfigurationRevision("fixture-revision"); return info;
    }
    private void seed(long task, long record, long targetId) throws Exception {
        sql("INSERT INTO dh_dataforge_access(id,task_name,engine_type,source_database_id,target_database_id) VALUES("
                + task + ",'fixture',1,20," + targetId + ")"); seedRun(task, record);
    }
    private void seedRun(long task, long record) throws Exception {
        try (var statement = control.prepareStatement("INSERT INTO dh_dataforge_access_task(id,data_access_id,run_status,protocol_version,"
                + "execution_stage,execution_token,lease_until,occupies_slot,stop_requested,config_json,release_revision,run_key,request_hash,execution_user_id,schedule_generation,effect_state)"
                + " VALUES(?,?,2,1,'EXECUTION_STARTED',?,clock_timestamp()+interval '5 minutes',true,false,'{\"version\":1}',1,?,repeat('a',64),99,1,'NONE')")) {
            statement.setLong(1, record); statement.setLong(2, task); statement.setString(3, UUID.randomUUID().toString());
            statement.setString(4, UUID.randomUUID().toString()); statement.executeUpdate();
        }
    }
    private void use(long task, long record, long targetId) throws Exception {

        access = new DataAccessDO(); access.setId(task); access.setSourceDatabaseId(20L); access.setTargetDatabaseId(targetId);
        run = new DataAccessTaskDO(); run.setId(record); run.setDataAccessId(task); run.setReleaseRevision(1);
        try (var statement = control.prepareStatement("SELECT execution_token,config_json::text FROM dh_dataforge_access_task WHERE id=?")) {
            statement.setLong(1, record); try (var row = statement.executeQuery()) { assertTrue(row.next()); run.setExecutionToken(row.getString(1)); run.setConfigJson(row.getString(2)); }
        }
        lease = new RunLease(record, run.getExecutionToken(), LocalDateTime.now().plusMinutes(5)); targetConnection.setDatabaseId(targetId);
    }
    private long count(String where) throws Exception { return Long.parseLong(scalar("SELECT count(*) FROM dh_dataforge_access_table_pin " + where)); }
    private String scalar(String sql) throws Exception { try (var statement = control.createStatement(); var row = statement.executeQuery(sql)) { assertTrue(row.next()); return row.getString(1); } }
    private void sql(String sql) throws Exception { try (var statement = control.createStatement()) { statement.execute(sql); } }
}
