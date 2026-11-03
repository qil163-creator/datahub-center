package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseColumnRespDTO;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.security.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncBatchObservationServiceTest {
    final SyncBatchObservationService service = new SyncBatchObservationService();
    final DataAccessTaskMapper runs = mock(DataAccessTaskMapper.class);
    final DatabaseMapper databases = mock(DatabaseMapper.class);
    final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    final ExecutionAccessService access = mock(ExecutionAccessService.class);
    final DatabaseApi databaseApi = mock(DatabaseApi.class);
    final SyncBatchQueryReqDTO request = new SyncBatchQueryReqDTO(20L, "public.orders", 99L);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "runMapper", runs);
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "resolver", resolver);
        ReflectionTestUtils.setField(service, "access", access);
        ReflectionTestUtils.setField(service, "databaseApi", databaseApi);
        var db = DatabaseDO.builder().id(20L).status(0).databaseSchema("public").build();
        db.setUpdateTime(LocalDateTime.of(2026, 9, 25, 10, 0));
        when(databases.selectById(20L)).thenReturn(db);
        when(resolver.resolve(db)).thenReturn(connection(ProductTypeEnum.POSTGRESQL, "public"));
        when(databaseApi.getQueryTableColumns(any())).thenReturn(CommonResult.success(List.of(
                new DatabaseColumnRespDTO("id", "bigint"), new DatabaseColumnRespDTO("org_id", "bigint"))));
        when(runs.selectSyncObservations("20", 21)).thenReturn(List.of(run(1)));
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }

    static AccessDatabaseResolver.ConnectionInfo connection(ProductTypeEnum product, String schema) {
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setProductType(product);
        connection.setSchema(schema);
        return connection;
    }

    static DataAccessTaskDO run(long id) {
        var evidence = new SyncBatchEvidenceDTO(1, id, 1, 10L, 20L, "source-secret-revision", "rev",
                "POSTGRESQL", "POSTGRESQL", "source_secret", "public", "plan", "TABLE_COPY", "CONFIRMED",
                true, 2L, 2L, 0L, List.of(
                new SyncBatchEvidenceDTO.Table("source_secret", "source_orders", "public", "orders", 2L, true,
                        new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 2L, 2L, 2, 2, 0L, false, false, false)),
                new SyncBatchEvidenceDTO.Table("source_secret", "secret", "public", "other_secret_table", 3L, true)));
        return DataAccessTaskDO.builder().id(id).attemptCount(1).rowVersion(4L).runStatus(3)
                .occupiesSlot(false).reviewRequired(false).stopRequested(false).effectState("COMPLETE")
                .syncEvidence(JsonUtils.toJsonString(evidence)).build();
    }
    @Test void projectsOnlyAuthorizedTargetAndChecksActorBeforeQuery() {
        var observation = service.observe(request);
        assertEquals(1, observation.batches().size());
        assertEquals("FULL_SCAN_TABLE", observation.batches().getFirst().transfer().operation());
        assertEquals(2L, observation.batches().getFirst().transfer().acknowledgedRows());
        assertFalse(observation.unscopedRunsPresent());
        String json = JsonUtils.toJsonString(observation);
        assertFalse(json.contains("secret")); assertFalse(json.contains("source_orders"));
        var ordered = inOrder(access, databaseApi, runs);
        ordered.verify(access).requireUserPermission(99L, "governance:quality-task:execute");
        ordered.verify(runs).selectSyncObservations("20", 21);
    }
    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MYSQL", "MARIADB"})
    void unqualifiedTableUsesResolvedNamespaceForAuthorizationAndBatchMatching(ProductTypeEnum product) {
        var db = DatabaseDO.builder().id(20L).status(0).databaseName("Warehouse").databaseSchema("").build();
        when(databases.selectById(20L)).thenReturn(db);
        when(resolver.resolve(db)).thenReturn(connection(product, "Warehouse"));
        var evidence = new SyncBatchEvidenceDTO(1, 1L, 1, 10L, 20L, "source-revision", "rev",
                "POSTGRESQL", product.name(), "public", "Warehouse", "plan", "TABLE_COPY", "CONFIRMED",
                true, 1L, 1L, 0L, List.of(
                new SyncBatchEvidenceDTO.Table("public", "source_orders", "Warehouse", "orders", 2L, true)));
        var run = run(1);
        run.setSyncEvidence(JsonUtils.toJsonString(evidence));
        when(runs.selectSyncObservations("20", 21)).thenReturn(List.of(run));

        var observation = service.observe(new SyncBatchQueryReqDTO(20L, "orders", 99L));

        assertEquals("Warehouse", observation.schema());
        assertEquals(product.name(), observation.product());
        assertEquals(1, observation.batches().size());
        assertEquals(2L, observation.batches().getFirst().rows());
        assertFalse(observation.unscopedRunsPresent());
        verify(databaseApi).getQueryTableColumns(argThat(columns ->
                "Warehouse".equals(columns.getSchemaName()) && "orders".equals(columns.getTableName())));
        var ordered = inOrder(access, resolver, databaseApi, runs);
        ordered.verify(access).requireUserPermission(99L, "governance:quality-task:execute");
        ordered.verify(resolver).resolve(db);
        ordered.verify(runs).selectSyncObservations("20", 21);
    }
    @Test void missingExecutionUserNeverQueries() {
        assertThrows(RuntimeException.class, () -> service.observe(new SyncBatchQueryReqDTO(20L, "orders", null)));
        verifyNoInteractions(runs);
    }
    @Test void noRpcInsidePlatformTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class, () -> service.observe(request));
        verifyNoInteractions(access, runs);
    }
    @Test void missingPhysicalTableNeverQueriesRuns() {
        when(databaseApi.getQueryTableColumns(any())).thenReturn(CommonResult.success(List.of()));
        assertThrows(RuntimeException.class, () -> service.observe(request));
        verifyNoInteractions(runs);
    }
    @Test void revokedPermissionNeverQueriesRuns() {
        doThrow(new IllegalStateException()).when(access).requireUserPermission(99L, "governance:quality-task:execute");
        assertThrows(RuntimeException.class, () -> service.observe(request));
        verifyNoInteractions(runs);
    }

    @Test void boundaryRegistrationDoesNotBypassAuthorizationOrTransactionGuard() {
        var boundaries = new SyncBoundaryService();
        ReflectionTestUtils.setField(boundaries, "observations", service);
        ReflectionTestUtils.setField(boundaries, "runs", runs);
        var requestWithKey = new SyncBoundaryReqDTO("quality:" + "a".repeat(64), request);
        doThrow(new IllegalStateException("revoked")).when(access).requireUserPermission(99L, "governance:quality-task:execute");
        assertThrows(RuntimeException.class, () -> boundaries.ensure(requestWithKey));
        verifyNoInteractions(runs);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(IllegalStateException.class, () -> boundaries.ensure(requestWithKey));
        verifyNoInteractions(runs);
    }
    @Test void unsupportedOrAmbiguousRelationNeverFallsBackToStringStripping() {
        assertThrows(RuntimeException.class, () -> service.observe(new SyncBatchQueryReqDTO(20L, "orders; SELECT 1", 99L)));
        when(resolver.resolve(any(DatabaseDO.class))).thenReturn(connection(ProductTypeEnum.ORACLE, "public"));
        assertThrows(RuntimeException.class, () -> service.observe(request));
        verifyNoInteractions(runs);
    }
    @Test void unknownNewRunMustNotDisappearBehindOldSuccess() {
        var unknown = run(2); unknown.setSyncEvidence(null);
        when(runs.selectSyncObservations("20", 21)).thenReturn(List.of(unknown, run(1)));
        var result = service.observe(request);
        assertTrue(result.unscopedRunsPresent()); assertEquals(1, result.batches().size());
    }
    @Test void malformedAndStaleAttemptsAreUnproven() {
        var malformed = run(2); malformed.setSyncEvidence("{");
        var stale = run(1); stale.setAttemptCount(2);
        when(runs.selectSyncObservations("20", 21)).thenReturn(List.of(malformed, stale));
        assertTrue(service.observe(request).unscopedRunsPresent());
        assertTrue(service.observe(request).batches().isEmpty());
    }
    @Test void boundedWindowDoesNotMeanCompleteHistory() {
        when(runs.selectSyncObservations("20", 21)).thenReturn(IntStream.rangeClosed(1, 21).mapToObj(i -> run(i)).toList());
        var result = service.observe(request);
        assertTrue(result.windowTruncated()); assertEquals(20, result.batches().size());
    }
    @Test void failedAndOccupiedResultsAreRetained() {
        var failed = run(1); failed.setRunStatus(5); failed.setOccupiesSlot(true);
        failed.setReviewRequired(true); failed.setEffectState("UNKNOWN");
        when(runs.selectSyncObservations("20", 21)).thenReturn(List.of(failed));
        var batch = service.observe(request).batches().getFirst();
        assertEquals(5, batch.runStatus()); assertTrue(batch.occupiesSlot()); assertTrue(batch.reviewRequired());
    }
}
