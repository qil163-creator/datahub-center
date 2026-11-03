package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.entity.*;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.*;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessGroupRewriteProperties;
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

/** Real platform PG transactions and component journal; native TD identities are trusted provider inputs,
 * not a claim of another TD server fixture. No target I/O is performed by this business adapter. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class AccessGroupRewritePostgreSqlTest {
    private Connection control;
    private String schema;
    private DriverManagerDataSource dataSource;
    private AccessGroupRewriteCoordinator coordinator;
    private AccessGroupRewriteProperties config;
    private DbSwitchRuntime runtime;
    private ConfiguredJdbcDrivers drivers;
    private DataAccessDO access;
    private DataAccessTaskDO run;
    private RunLease lease;
    private SourceDataSourceProperties source;
    private TargetDataSourceProperties target;
    private AccessDatabaseResolver.ConnectionInfo sourceConnection, targetConnection;
    private static final String LOGICAL = "1".repeat(64), PHYSICAL = "2".repeat(64), STRUCTURE = "3".repeat(64);

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url"), user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "access_group_" + UUID.randomUUID().toString().replace("-", "");
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
        sql(Files.readString(root.resolve("sql/postgresql/migrations/0169_datahub_remove-business-tenancy.sql")).replace("datahub-center", schema));
        dataSource = new DriverManagerDataSource(url, user, "");
        config = new AccessGroupRewriteProperties(); config.setEnabled(true); config.setControlSchema(schema);
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        config.setCurrentKeyId("fixture"); config.setKeyring(Map.of("fixture", Base64.getEncoder().encodeToString(key))); Arrays.fill(key, (byte) 0);
        config.setExclusiveTasks(Set.of("10", "11"));
        runtime = mock(DbSwitchRuntime.class); drivers = mock(ConfiguredJdbcDrivers.class);
        when(runtime.drivers()).thenReturn(drivers); when(runtime.options()).thenReturn(DbSwitchOptions.defaults());
        for (var product : List.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.TDENGINE)) {
            var driver = mock(ConfiguredJdbcDrivers.Binding.class);
            when(driver.profileId()).thenReturn("fixture-" + product.name().toLowerCase(Locale.ROOT));
            when(driver.fingerprint()).thenReturn(product == ProductTypeEnum.POSTGRESQL ? "a".repeat(64) : "b".repeat(64));
            when(drivers.find(product)).thenReturn(Optional.of(driver));
        }
        source = new SourceDataSourceProperties(); source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        target = new TargetDataSourceProperties(); target.setTargetProductType(ProductTypeEnum.TDENGINE);
        target.setChangeDataSync(true); target.setChangeDataSyncType("PK_ORDER_COMPARE");
        sourceConnection = connection(20, ProductTypeEnum.POSTGRESQL); targetConnection = connection(30, ProductTypeEnum.TDENGINE);
        seed(10, 100, 30); use(10, 100, 30);
        coordinator = new AccessGroupRewriteCoordinator(dataSource, config, runtime);
    }
    @AfterEach void cleanup() throws Exception {

        if (control != null) try {
            if (schema != null && schema.matches("access_group_[a-f0-9]{32}")) { sql("ROLLBACK"); sql("DROP SCHEMA " + schema + " CASCADE"); }
        } finally { control.close(); }
    }
    @Test void deploymentDisabledIsNoopButEnabledInvalidConfigurationCannotSilentlyProceed() {
        config.setEnabled(false); assertNull(prepare()); verifyNoInteractions(runtime);
        config.setEnabled(true); config.setKeyring(Map.of()); assertThrows(IllegalArgumentException.class, this::prepare);
        config.setExclusiveTasks(Set.of()); assertThrows(IllegalArgumentException.class, this::prepare);
    }
    @Test void completedEmptyJournalReleasesOnlyAfterActualTerminationAndKeepsThePermanentPin() throws Exception {
        var handle = prepare(); var binding = bind(handle); binding.checkOwnership();
        assertEquals(1, count("WHERE occupied AND NOT executor_drained"));
        assertFalse(handle.finished(false)); assertEquals(1, count("WHERE occupied AND NOT executor_drained"));
        assertFalse(handle.hasConfirmedExecutorDrain());
        assertTrue(handle.finished(true)); assertTrue(handle.finished(true));
        assertTrue(handle.hasConfirmedExecutorDrain());
        assertEquals(1, count("WHERE NOT occupied AND executor_drained"));
        verify(runtime, never()).jdbcMetadata(any());
    }
    @Test void pendingMaterialPersistsDrainEvidenceButCannotReleaseOrLetAnOldWriterContinue() throws Exception {
        var handle = prepare(); var binding = bind(handle); var key = key(binding, "4".repeat(64));
        binding.store().prepare(key, lease.token(), material(binding), -1);
        assertFalse(handle.finished(true));
        assertTrue(handle.hasConfirmedExecutorDrain());
        assertEquals(1, count("WHERE occupied AND executor_drained"));
        assertThrows(RuntimeException.class, binding::checkOwnership);
        assertThrows(RuntimeException.class, () -> binding.store().transition(key, lease.token(), 0, GroupRewriteRecord.Phase.PREPARED, GroupRewriteRecord.Phase.DELETE_INTENT));
        assertEquals("PREPARED", scalar("SELECT phase FROM dh_dataforge_access_group_recovery"));
    }
    @Test void authenticAppliedHeadersCanBeScannedAfterDrainWithTheDedicatedCompletionGuard() throws Exception {
        var handle = prepare(); var binding = bind(handle); applied(binding, "4".repeat(64));
        assertTrue(handle.finished(true)); assertEquals(1, count("WHERE NOT occupied AND executor_drained"));
        assertEquals("APPLIED", scalar("SELECT phase FROM dh_dataforge_access_group_recovery"));
    }
    @Test void forgedAppliedPhaseIsAuthenticatedInsteadOfHidingPendingWork() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        sql("UPDATE dh_dataforge_access_group_recovery SET phase='APPLIED'");
        assertFalse(handle.finished(true)); assertEquals(1, count("WHERE occupied AND executor_drained"));
    }
    @Test void journalCannotMovePendingWorkOutsideItsPinnedPhysicalGeneration() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        var rejected = assertThrows(SQLException.class, () -> sql("UPDATE dh_dataforge_access_group_recovery SET resource='" + "e".repeat(64) + "'"));
        assertEquals("23503", rejected.getSQLState());
        assertEquals(PHYSICAL, scalar("SELECT resource FROM dh_dataforge_access_group_recovery"));
        assertFalse(handle.finished(true)); assertEquals(1, count("WHERE occupied"));
    }
    @Test void mutablePinTamperingCannotAuthorizeTargetWritesOrRelease() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        sql("UPDATE dh_dataforge_access_group_pin SET revision=revision+1");
        assertThrows(RuntimeException.class, binding::checkOwnership);
        assertFalse(handle.finished(true)); assertEquals(1, count("WHERE occupied"));
    }
    @Test void immutablePinTamperingCannotFollowAReplacementTarget() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        sql("UPDATE dh_dataforge_access_group_pin SET physical_fingerprint='" + "e".repeat(64) + "'");
        assertThrows(RuntimeException.class, binding::checkOwnership); assertFalse(handle.finished(true));
    }
    @Test void releasedPinStillRejectsDropRecreateAndConfigurationChanges() throws Exception {
        var first = prepare(); bind(first); assertTrue(first.finished(true));
        var rebuilt = prepare(); assertThrows(RuntimeException.class, () -> rebuilt.resolve("measurements", "child", LOGICAL, "e".repeat(64), STRUCTURE));
        sourceConnection.setConfigurationRevision("changed-source-profile-revision");
        assertThrows(RuntimeException.class, () -> bind(prepare()));
        assertEquals(PHYSICAL, scalar("SELECT physical_fingerprint FROM dh_dataforge_access_group_pin"));
    }
    @Test void logicalTargetExclusionCrossesTasksAndDatasourceAliases() throws Exception {
        var first = prepare(); bind(first);
        seed(11, 101, 31); use(11, 101, 31);
        assertThrows(RuntimeException.class, () -> bind(prepare()));
        assertEquals(1, count("WHERE occupied"));
    }
    @Test void concurrentOwnersOfOneLogicalTargetHaveExactlyOneDurableWinner() throws Exception {
        var first = prepare(); seed(11, 101, 31); use(11, 101, 31); var second = prepare();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = List.of(first, second).stream().map(handle -> pool.submit(() -> {
                assertTrue(gate.await(5, TimeUnit.SECONDS));
                try { bind(handle); return 1; } catch (RuntimeException rejected) { return 0; }
            })).toList();
            gate.countDown();
            assertEquals(1, attempts.get(0).get(15, TimeUnit.SECONDS) + attempts.get(1).get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, count("WHERE occupied"));
    }
    @Test void lostPinCommitAcknowledgementKeepsTheCommittedOccupancyUnconfirmed() throws Exception {
        var uncertainSource = new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = dataSource.getConnection();
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, arguments) -> {
                            try {
                                Object result = method.invoke(actual, arguments);
                                if (method.getName().equals("commit")) throw new SQLException("Fixture commit response unavailable", "08006");
                                return result;
                            } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { throw new java.sql.SQLFeatureNotSupportedException(); }
        };
        coordinator = new AccessGroupRewriteCoordinator(uncertainSource, config, runtime);
        var handle = prepare(); assertThrows(RuntimeException.class, () -> bind(handle));
        assertFalse(handle.finished(true)); assertEquals(1, count("WHERE occupied AND NOT executor_drained"));
        assertFalse(handle.hasConfirmedExecutorDrain());
    }
    @Test void taskMismatchAndCrossScopeJournalKeysAreRejected() {
        run.setDataAccessId(11L); assertThrows(IllegalArgumentException.class, this::prepare);
        run.setDataAccessId(10L); var binding = bind(prepare());
        assertThrows(RuntimeException.class, () -> binding.store().read(new GroupRewriteKey("f".repeat(64), PHYSICAL, "4".repeat(64))));
        assertThrows(RuntimeException.class, () -> binding.store().read(new GroupRewriteKey(binding.scope(), "f".repeat(64), "4".repeat(64))));
    }
    @Test void expiredLeaseNeverProvidesDrainButRetainedRuntimeMayReportActualTermination() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        sql("UPDATE dh_dataforge_access_task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=100");
        assertThrows(RuntimeException.class, binding::checkOwnership); assertFalse(handle.finished(false));
        assertEquals(1, count("WHERE occupied AND NOT executor_drained"));
        assertTrue(handle.finished(true));
        assertEquals(1, count("WHERE NOT occupied AND executor_drained"));
    }
    @Test void replacedExecutionTokenCannotBeOverriddenByAnOldRuntimeCompletion() throws Exception {
        var handle = prepare(); bind(handle);
        try (var statement = control.prepareStatement("UPDATE dh_dataforge_access_task SET execution_token=?,lease_until=clock_timestamp()+interval '5 minutes' WHERE id=100")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.executeUpdate();
        }
        assertFalse(handle.finished(true)); assertEquals(1, count("WHERE occupied AND NOT executor_drained"));
        use(10, 100, 30);
        assertThrows(RuntimeException.class, () -> bind(prepare()));
    }
    @Test void stopRejectsTargetWorkButAllowsConfirmedDrainAndCompletion() throws Exception {
        var handle = prepare(); var binding = bind(handle); applied(binding, "4".repeat(64));
        sql("UPDATE dh_dataforge_access_task SET stop_requested=true WHERE id=100");
        assertThrows(RuntimeException.class, binding::checkOwnership);
        assertTrue(handle.finished(true)); assertEquals(1, count("WHERE NOT occupied AND executor_drained"));
    }
    @Test void onePendingTablePreventsReleaseOfOtherCompletedTables() throws Exception {
        var handle = prepare(); var first = bind(handle);
        first.store().prepare(key(first, "4".repeat(64)), lease.token(), material(first), -1);
        var second = handle.resolve("measurements", "other_child", "5".repeat(64), "6".repeat(64), STRUCTURE);
        second.store().prepare(new GroupRewriteKey(second.scope(), second.physicalFingerprint(), "4".repeat(64)), lease.token(), material(second), -1);
        assertFalse(handle.finished(true)); assertEquals(2, count("WHERE occupied"));
        assertEquals(2, count("WHERE occupied AND executor_drained"));
    }

    @Test void explicitRecoveryTransfersAllPinsOnlyAfterSignedPriorDrainAndUsesRecoveryMode() throws Exception {
        var previous = prepare(); var binding = bind(previous); var key = key(binding, "4".repeat(64));
        binding.store().prepare(key, lease.token(), material(binding), -1);
        assertFalse(previous.finished(true));
        var recovery = claimRecovery(UUID.randomUUID().toString());
        assertEquals(1, count("WHERE occupied AND NOT executor_drained"));
        assertThrows(RuntimeException.class, binding::checkOwnership);
        assertFalse(previous.finished(true)); // An old retained handle cannot re-sign the new executor's drain.
        var tables = recovery.pendingTables(); assertEquals(1, tables.size());
        var table = tables.getFirst(); assertEquals(List.of("ts", "id"), table.fields()); assertEquals(List.of("ts", "id"), table.primaryKeys());
        assertEquals(GroupRewriteBinding.ResumeMode.RECONCILE_AFTER_PRIOR_EXECUTOR_EXIT, table.binding().resumeMode());
        var record = table.binding().store().read(key).orElseThrow();
        record = table.binding().store().claimRecovery(key, record.revision(), table.binding().ownerToken()).orElseThrow();
        for (var phase : List.of(GroupRewriteRecord.Phase.DELETE_INTENT, GroupRewriteRecord.Phase.RESTORE_INTENT, GroupRewriteRecord.Phase.APPLIED))
            record = table.binding().store().transition(key, table.binding().ownerToken(), record.revision(), record.phase(), phase).orElseThrow();
        assertTrue(recovery.finished(true)); assertEquals(1, count("WHERE NOT occupied AND executor_drained"));
        assertResolved();
    }
    @Test void recoveryRejectsLeaseAgeWithoutSignedDrainAndKeepsThePriorPinOwner() throws Exception {
        var handle = prepare(); bind(handle);
        String original = scalar("SELECT owner_token FROM dh_dataforge_access_group_pin");
        sql("UPDATE dh_dataforge_access_task SET lease_until=clock_timestamp()-interval '1 day' WHERE id=100");
        assertThrows(RuntimeException.class, () -> claimRecovery(UUID.randomUUID().toString()));
        assertEquals(original, scalar("SELECT owner_token FROM dh_dataforge_access_group_pin"));
        assertEquals(1, count("WHERE occupied AND NOT executor_drained"));
    }
    @Test void recoveryRejectsChangedFrozenProfileAndAuthenticatesSignedDrainState() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        assertFalse(handle.finished(true));
        sourceConnection.setConfigurationRevision("different-revision");
        assertThrows(RuntimeException.class, () -> claimRecovery(UUID.randomUUID().toString()));
        sourceConnection.setConfigurationRevision("fixture-revision");
        sql("UPDATE dh_dataforge_access_group_pin SET executor_drained=false");
        assertThrows(RuntimeException.class, () -> claimRecovery(UUID.randomUUID().toString()));
        assertEquals(1, count("WHERE occupied"));
    }
    @Test void recoveryCannotFollowRebuiltTargetOrUnregisteredRoute() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        assertFalse(handle.finished(true)); var recovery = claimRecovery(UUID.randomUUID().toString());
        assertThrows(RuntimeException.class, () -> recovery.resolve("measurements", "child", LOGICAL, "e".repeat(64), STRUCTURE));
        assertThrows(RuntimeException.class, () -> recovery.resolve("measurements", "new_child", LOGICAL, PHYSICAL, STRUCTURE));
        assertEquals(1, count("WHERE occupied AND NOT executor_drained"));
    }
    @Test void uncertainRecoveryCloseCannotGrantAnotherRecoveryEvenAfterItsLeaseExpires() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        assertFalse(handle.finished(true)); var recovery = claimRecovery(UUID.randomUUID().toString());
        assertFalse(recovery.finished(false));
        sql("UPDATE dh_dataforge_access_task SET reconcile_lease_until=clock_timestamp()-interval '1 hour' WHERE id=100");
        assertThrows(RuntimeException.class, () -> claimRecovery(UUID.randomUUID().toString()));
        assertEquals(1, count("WHERE occupied AND NOT executor_drained"));
    }
    @Test void completedRuntimeCanRecordDrainAfterScannerChangedRunToUnknownAndClearedLease() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        sql("UPDATE dh_dataforge_access_task SET run_status=5,review_required=true,execution_token=null,lease_until=null WHERE id=100");
        assertFalse(handle.finished(true)); assertEquals(1, count("WHERE occupied AND executor_drained"));
        assertEquals(1, claimRecovery(UUID.randomUUID().toString()).pendingTables().size());
    }
    @Test void stopAfterRecoveryAdmissionRejectsFurtherWorkButActualCompletionMayDrain() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        assertFalse(handle.finished(true)); var recovery = claimRecovery(UUID.randomUUID().toString());
        sql("UPDATE dh_dataforge_access_task SET stop_requested=true WHERE id=100");
        assertThrows(RuntimeException.class, recovery::pendingTables);
        assertFalse(recovery.finished(true)); assertEquals(1, count("WHERE occupied AND executor_drained"));
    }
    @Test void resolutionUsesSameCallerConnectionAndRejectsPendingOrTamperedCompletionEvidence() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        assertThrows(RuntimeException.class, this::assertResolved);
        applied(binding, "4".repeat(64)); assertTrue(handle.finished(true));
        var forbidden = mock(javax.sql.DataSource.class);
        var sameConnection = new AccessGroupRewriteCoordinator(forbidden, config, runtime);
        control.setAutoCommit(false);
        try { sameConnection.assertResolved(control,10,100); control.rollback(); }
        finally { control.setAutoCommit(true); }
        verifyNoInteractions(forbidden);
        sql("UPDATE dh_dataforge_access_group_pin SET revision=revision+1");
        assertThrows(RuntimeException.class, this::assertResolved);
    }
    @Test void recoveryHeaderTamperingFailsBeforeAnyTargetExecution() throws Exception {
        var handle = prepare(); var binding = bind(handle);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        assertFalse(handle.finished(true)); var recovery = claimRecovery(UUID.randomUUID().toString());
        sql("UPDATE dh_dataforge_access_group_recovery SET phase='APPLIED'");
        assertThrows(RuntimeException.class, recovery::pendingTables);
        assertFalse(recovery.finished(true)); assertEquals(1, count("WHERE occupied AND executor_drained"));
        verify(runtime, never()).synchronizer(any(), any());
    }
    @Test void rolledBackRecoveryAdmissionCannotAuthorizeWorkOrInvalidateThePriorDrain() throws Exception {
        var original = prepare(); var binding = bind(original);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        assertFalse(original.finished(true));
        String token = UUID.randomUUID().toString();
        var admission = coordinator.prepareRecovery(access, run, token, source, target, sourceConnection, targetConnection);
        sql("UPDATE dh_dataforge_access_task SET run_status=5,review_required=true,execution_token=null,lease_until=null WHERE id=100");
        AccessGroupRewriteCoordinator.RecoveryHandle rolledBack;
        control.setAutoCommit(false);
        try {
            try (var update = control.prepareStatement("UPDATE dh_dataforge_access_task SET reconcile_token=?,reconcile_lease_until=clock_timestamp()+interval '5 minutes' WHERE id=100")) {
                update.setString(1, token); update.executeUpdate();
            }
            rolledBack = coordinator.admitRecovery(control, admission); control.rollback();
        } finally { control.setAutoCommit(true); }
        assertThrows(RuntimeException.class, rolledBack::assertOwnership);
        assertThrows(RuntimeException.class, rolledBack::pendingTables);
        assertFalse(rolledBack.finished(true));
        assertEquals(1, count("WHERE occupied AND executor_drained"));
        assertEquals(lease.token(), scalar("SELECT owner_token FROM dh_dataforge_access_group_pin"));
    }
    @Test void everyPendingMaterialMustHaveOneFrozenFieldAndKeyContractBeforeTargetWork() throws Exception {
        var original = prepare(); var binding = bind(original);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        var incompatible = GroupRewriteMaterial.create(binding.fingerprint(), PHYSICAL, STRUCTURE,
                List.of("ts", "different_key"), List.of("ts", "different_key"),
                List.of(new Object[]{2L, 1L}, new Object[]{2L, 2L}), List.<Object[]>of(new Object[]{2L, 1L}), binding.limits());
        binding.store().prepare(key(binding, "5".repeat(64)), lease.token(), incompatible, -1);
        assertFalse(original.finished(true)); var recovery = claimRecovery(UUID.randomUUID().toString());
        assertThrows(RuntimeException.class, recovery::pendingTables);
        assertFalse(recovery.finished(true)); assertEquals(1, count("WHERE occupied AND executor_drained"));
        verify(runtime, never()).synchronizer(any(), any());
    }
    @Test void resolutionOfNonGroupRunNeedsNoDeploymentKeysButCannotCrossTask() throws Exception {
        config.setEnabled(false); config.setKeyring(Map.of()); assertResolved();
        sql("UPDATE dh_dataforge_access_task SET data_access_id=11 WHERE id=100");
        assertThrows(RuntimeException.class, this::assertResolved);
    }
    @Test void changingUnsignedLookupOwnerCannotHideAnOccupiedPinFromResolution() throws Exception {
        var original = prepare(); var binding = bind(original);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        assertFalse(original.finished(true));
        sql("UPDATE dh_dataforge_access_group_pin SET owner_run_id=999999");
        assertThrows(RuntimeException.class, this::assertResolved);
    }
    @Test void recoveryCannotSilentlyOmitATableWhoseOwnerWasTampered() throws Exception {
        var original = prepare(); var first = bind(original);
        first.store().prepare(key(first, "4".repeat(64)), lease.token(), material(first), -1);
        var other = original.resolve("measurements", "other_child", "5".repeat(64), "6".repeat(64), STRUCTURE);
        other.store().prepare(key(other, "4".repeat(64)), lease.token(), material(other), -1);
        assertFalse(original.finished(true));
        sql("UPDATE dh_dataforge_access_group_pin SET owner_run_id=999999 WHERE table_name='other_child'");
        assertThrows(RuntimeException.class, () -> claimRecovery(UUID.randomUUID().toString()));
        assertEquals(2, count("WHERE occupied AND executor_drained"));
        assertEquals(1, count("WHERE owner_run_id=100"));
    }
    @Test void statusIsPlatformReadOnlyAndRequiresActualDrainNotLeaseAge() throws Exception {
        assertFalse(status().applicable());
        var original = prepare(); var binding = bind(original);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        sql("UPDATE dh_dataforge_access_task SET run_status=5,review_required=true,execution_token=null,lease_until=null WHERE id=100");
        var pending = status(); assertTrue(pending.applicable()); assertFalse(pending.ready());
        assertTrue(pending.message().contains("尚未确认退出"));
        assertFalse(original.finished(true));
        String revision = scalar("SELECT revision FROM dh_dataforge_access_group_pin");
        assertTrue(status().ready()); assertEquals(revision, scalar("SELECT revision FROM dh_dataforge_access_group_pin"));
        verify(runtime, never()).synchronizer(any(), any());
    }
    @Test void statusAuthenticatesOwnerBeforeFilteringAndDoesNotExposeSecrets() throws Exception {
        var original = prepare(); bind(original);
        sql("UPDATE dh_dataforge_access_group_pin SET owner_run_id=999999");
        var status = status(); assertTrue(status.applicable()); assertFalse(status.ready());
        assertTrue(status.message().contains("完整性校验"));
        assertFalse(status.toString().contains(config.getKeyring().get("fixture")));
    }
    @Test void statusDistinguishesActiveRecoveryReleasedGroupsAndUnavailableDeployment() throws Exception {
        var original = prepare(); var binding = bind(original); applied(binding, "4".repeat(64));
        assertTrue(original.finished(true));
        assertTrue(status().message().contains("仍须核查")); assertFalse(status().ready());
        config.setEnabled(false); assertTrue(status().message().contains("未启用"));
        config.setEnabled(true); config.setKeyring(Map.of()); assertTrue(status().message().contains("密钥配置不可用"));
    }
    @Test void activeRecoveryStatusNeverGrantsExecutionAndCrossTaskStatusIsRejected() throws Exception {
        var original = prepare(); var binding = bind(original);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        assertFalse(original.finished(true)); claimRecovery(UUID.randomUUID().toString());
        assertFalse(status().ready()); assertTrue(status().message().contains("恢复已受理"));
        sql("UPDATE dh_dataforge_access_task SET data_access_id=11 WHERE id=100"); assertThrows(RuntimeException.class, this::status);
    }
    @Test void lostDrainCommitAcknowledgementIsNotAConfirmedReceiptAndCanBeRetriedByTheRetainedHandle() throws Exception {
        var loseResponse = new java.util.concurrent.atomic.AtomicBoolean();
        var uncertain = new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var actual = dataSource.getConnection();
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        var result = method.invoke(actual, args);
                        if (method.getName().equals("commit") && loseResponse.getAndSet(false)) throw new SQLException("Fixture commit response unavailable", "08006");
                        return result;
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String username, String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        };
        coordinator = new AccessGroupRewriteCoordinator(uncertain, config, runtime);
        var handle = prepare(); var binding = bind(handle);
        binding.store().prepare(key(binding, "4".repeat(64)), lease.token(), material(binding), -1);
        loseResponse.set(true);
        assertFalse(handle.finished(true)); assertFalse(handle.hasConfirmedExecutorDrain());
        assertEquals(1, count("WHERE occupied AND executor_drained"));
        assertFalse(handle.finished(true)); assertTrue(handle.hasConfirmedExecutorDrain());
    }
    @Test void scriptDropTruncateAndCustomDdlAreRejectedBeforePinAdmission() throws Exception {
        for (String kind : List.of("before", "after", "drop", "truncate", "ddl")) {
            source.setBeforeSqlScripts(kind.equals("before") ? "SELECT 1" : null);
            target.setAfterSqlScripts(kind.equals("after") ? "SELECT 1" : null);
            target.setTargetDrop(kind.equals("drop")); target.setTargetTruncateData(kind.equals("truncate"));
            target.setCustomDdlMap(kind.equals("ddl") ? Map.of("child", "CREATE TABLE child(x int)") : null);
            assertThrows(IllegalArgumentException.class, this::prepare);
        }
        assertEquals(0, count(""));
    }
    @Test void deploymentSecretsNeverAppearInDiagnosticStringsAndSnapshotsDoNotFollowMutation() {
        String encoded = config.getKeyring().get("fixture"); var deployment = config.authorize(10);
        assertFalse(config.toString().contains(encoded)); assertFalse(deployment.toString().contains(encoded));
        assertThrows(UnsupportedOperationException.class, () -> config.getKeyring().clear());
        config.setKeyring(Map.of()); assertEquals(32, deployment.keys().get("fixture").getEncoded().length);
    }

    private AccessGroupRewriteCoordinator.Handle prepare() { return coordinator.prepare(access, run, lease, source, target, sourceConnection, targetConnection); }
    private AccessGroupRewriteCoordinator.RecoveryHandle claimRecovery(String token) throws Exception {
        var admission = coordinator.prepareRecovery(access, run, token, source, target, sourceConnection, targetConnection);
        sql("UPDATE dh_dataforge_access_task SET run_status=5,review_required=true,execution_token=null,lease_until=null WHERE id=100");
        control.setAutoCommit(false);
        try {
            try (var statement = control.prepareStatement("UPDATE dh_dataforge_access_task SET reconcile_token=?,reconcile_lease_until=clock_timestamp()+interval '5 minutes',stop_requested=false WHERE id=100")) {
                statement.setString(1, token); statement.executeUpdate();
            }
            var handle = coordinator.admitRecovery(control, admission); control.commit(); return handle;
        } catch (Exception failure) { control.rollback(); throw failure; }
        finally { control.setAutoCommit(true); }
    }
    private void assertResolved() throws Exception {
        control.setAutoCommit(false);
        try { coordinator.assertResolved(control,10,100); }
        finally { control.rollback(); control.setAutoCommit(true); }
    }
    private AccessGroupRewriteCoordinator.RecoveryStatus status() throws Exception {
        control.setReadOnly(true); control.setAutoCommit(false);
        try { return coordinator.recoveryStatus(control,10,100); }
        finally { control.rollback(); control.setAutoCommit(true); control.setReadOnly(false); }
    }
    private GroupRewriteBinding bind(AccessGroupRewriteCoordinator.Handle handle) { return handle.resolve("measurements", "child", LOGICAL, PHYSICAL, STRUCTURE); }
    private GroupRewriteKey key(GroupRewriteBinding binding, String group) { return new GroupRewriteKey(binding.scope(), binding.physicalFingerprint(), group); }
    private GroupRewriteMaterial material(GroupRewriteBinding binding) {
        return GroupRewriteMaterial.create(binding.fingerprint(), binding.physicalFingerprint(), STRUCTURE, List.of("ts", "id"), List.of("ts", "id"),
                List.of(new Object[]{1L, 1L}, new Object[]{1L, 2L}), List.<Object[]>of(new Object[]{1L, 1L}), binding.limits());
    }
    private void applied(GroupRewriteBinding binding, String group) {
        var key = key(binding, group); var record = binding.store().prepare(key, lease.token(), material(binding), -1);
        for (var phase : List.of(GroupRewriteRecord.Phase.DELETE_INTENT, GroupRewriteRecord.Phase.RESTORE_INTENT, GroupRewriteRecord.Phase.APPLIED))
            record = binding.store().transition(key, lease.token(), record.revision(), record.phase(), phase).orElseThrow();
    }
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
    private long count(String where) throws Exception { return Long.parseLong(scalar("SELECT count(*) FROM dh_dataforge_access_group_pin " + where)); }
    private String scalar(String sql) throws Exception { try (var statement = control.createStatement(); var row = statement.executeQuery(sql)) { assertTrue(row.next()); return row.getString(1); } }
    private void sql(String sql) throws Exception { try (var statement = control.createStatement()) { statement.execute(sql); } }
}
