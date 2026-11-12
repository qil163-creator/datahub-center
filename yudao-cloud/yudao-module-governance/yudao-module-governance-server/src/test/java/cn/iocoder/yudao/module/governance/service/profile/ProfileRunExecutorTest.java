package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileColumn;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileColumnKind;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileColumnStats;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileTopValuesQuery.ValueCount;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileRunMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileValueMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 探查执行编排：CAS 领取、续租、失联恢复、次数用尽、敏感 Top 值不落库与失败原因脱敏。
 *
 * <p>ProfileRunMapper 由内存模型按与 SQL 相同的 CAS 条件模拟；真实 SQL 语义由 {@code ProfileRunPostgreSqlTest} 覆盖。</p>
 */
class ProfileRunExecutorTest {

    private static final long RUN_ID = 7L;
    private static final long TABLE_ID = 70L;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-12T08:00:00Z"));
    private final Map<Long, ProfileRunDO> runs = new LinkedHashMap<>();
    private final List<ProfileColumnDO> insertedColumns = new ArrayList<>();
    private final List<ProfileValueDO> insertedValues = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1000);

    private ProfileRunExecutor executor;
    private ProfileRunMapper runMapper;
    private ProfileJdbcRunner jdbcRunner;
    private ProfileSensitivityResolver sensitivityResolver;
    private ProfileValueMasker masker;
    private MetaColumnDO phone;
    private MetaColumnDO status;

    @BeforeEach
    void setUp() throws Exception {
        runMapper = mock(ProfileRunMapper.class);
        installCasModel();
        ProfileColumnMapper columnMapper = mock(ProfileColumnMapper.class);
        when(columnMapper.insert(any(ProfileColumnDO.class))).thenAnswer(call -> {
            ProfileColumnDO column = call.getArgument(0);
            column.setId(ids.incrementAndGet());
            insertedColumns.add(column);
            return 1;
        });
        ProfileValueMapper valueMapper = mock(ProfileValueMapper.class);
        when(valueMapper.insertBatch(anyList())).thenAnswer(call -> {
            Collection<ProfileValueDO> values = call.getArgument(0);
            insertedValues.addAll(values);
            return true;
        });
        ProfileResultWriter writer = new ProfileResultWriter();
        ReflectionTestUtils.setField(writer, "runMapper", runMapper);
        ReflectionTestUtils.setField(writer, "columnMapper", columnMapper);
        ReflectionTestUtils.setField(writer, "valueMapper", valueMapper);

        MetaTableDO table = new MetaTableDO();
        table.setId(TABLE_ID);
        table.setDatasourceId(9L);
        table.setTableName("customer");
        MetaDbDO db = new MetaDbDO();
        db.setSchemaName("crm");
        phone = column(1L, "phone", "varchar(20)", 1);
        status = column(2L, "status", "varchar(8)", 2);
        ProfileTargetLoader loader = mock(ProfileTargetLoader.class);
        when(loader.load(TABLE_ID)).thenReturn(new ProfileTargetLoader.ProfileTarget(table, db, List.of(phone, status)));

        MasterDatabaseResolver resolver = mock(MasterDatabaseResolver.class);
        MasterDatabaseResolver.ConnectionInfo conn = new MasterDatabaseResolver.ConnectionInfo();
        conn.setProductType(ProductTypeEnum.POSTGRESQL);
        when(resolver.resolve(eq(9L), any())).thenReturn(conn);

        sensitivityResolver = mock(ProfileSensitivityResolver.class);
        when(sensitivityResolver.resolve(any(), any(), anyList())).thenReturn(Map.of(
                "phone", sensitivity("phone", true),
                "status", sensitivity("status", false)));
        masker = ProfileRunExecutorTest::metadataOnlyMasker;

        jdbcRunner = mock(ProfileJdbcRunner.class);
        when(jdbcRunner.collect(any(), eq("crm"), eq("customer"), anyList(), anyInt(), anyInt(), any()))
                .thenAnswer(call -> {
                    Runnable heartbeat = call.getArgument(6);
                    heartbeat.run();
                    return rawProfile();
                });

        executor = new ProfileRunExecutor();
        ReflectionTestUtils.setField(executor, "runMapper", runMapper);
        ReflectionTestUtils.setField(executor, "targetLoader", loader);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "sensitivityResolver", sensitivityResolver);
        ReflectionTestUtils.setField(executor, "valueMasker", masker);
        ReflectionTestUtils.setField(executor, "jdbcRunner", jdbcRunner);
        ReflectionTestUtils.setField(executor, "resultWriter", writer);
        executor.setClock(clock);

        ProfileRunDO pending = new ProfileRunDO();
        pending.setId(RUN_ID);
        pending.setMetaTableId(TABLE_ID);
        pending.setStatus(ProfileRunDO.STATUS_PENDING);
        pending.setSampleLimit(100_000);
        pending.setAttemptCount(0);
        runs.put(RUN_ID, pending);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    // ========== 领取与租约 ==========

    @Test
    void pendingRunIsClaimedOnceAndCompleted() {
        assertThat(executor.execute(RUN_ID)).isTrue();
        ProfileRunDO run = runs.get(RUN_ID);
        assertThat(run.getStatus()).isEqualTo(ProfileRunDO.STATUS_SUCCESS);
        assertThat(run.getAttemptCount()).isEqualTo(1);
        assertThat(run.getLeaseOwner()).isNull();
        assertThat(run.getSampleRows()).isEqualTo(100L);
        assertThat(run.getTotalRows()).isEqualTo(5000L);
        assertThat(insertedColumns).hasSize(2).allSatisfy(column -> assertThat(column.getRunId()).isEqualTo(RUN_ID));
        // 已结束的记录不会被再次领取
        assertThat(executor.execute(RUN_ID)).isFalse();
        assertThat(insertedColumns).hasSize(2);
    }

    @Test
    void concurrentClaimOfARunningRecordIsRejected() throws Exception {
        List<Boolean> nested = new ArrayList<>();
        when(jdbcRunner.collect(any(), any(), any(), anyList(), anyInt(), anyInt(), any())).thenAnswer(call -> {
            // 第一个执行者持有有效租约期间，另一个执行者（或节点）尝试领取同一记录
            nested.add(executor.execute(RUN_ID));
            return rawProfile();
        });
        assertThat(executor.execute(RUN_ID)).isTrue();
        assertThat(nested).containsExactly(false);
        assertThat(runs.get(RUN_ID).getAttemptCount()).isEqualTo(1);
        assertThat(insertedColumns).hasSize(2);
    }

    @Test
    void lostLeaseAbortsWithoutWritingAndExpiredRunIsRecoveredByAnotherExecutor() throws Exception {
        when(jdbcRunner.collect(any(), any(), any(), anyList(), anyInt(), anyInt(), any())).thenAnswer(call -> {
            // 当前执行者失联超过租约，另一节点接管
            clock.advance(ProfileRunExecutor.LEASE.plusSeconds(1));
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            assertThat(runMapper.claim(RUN_ID, "other-node", now, now.plus(ProfileRunExecutor.LEASE),
                    ProfileRunExecutor.MAX_ATTEMPTS)).isEqualTo(1);
            Runnable heartbeat = call.getArgument(6);
            heartbeat.run();
            return rawProfile();
        });
        assertThat(executor.execute(RUN_ID)).isTrue();
        ProfileRunDO run = runs.get(RUN_ID);
        assertThat(run.getStatus()).isEqualTo(ProfileRunDO.STATUS_RUNNING);
        assertThat(run.getLeaseOwner()).isEqualTo("other-node");
        assertThat(insertedColumns).isEmpty();

        // 接管者也失联：租约过期后定时任务重新领取并完成
        org.mockito.Mockito.doReturn(rawProfile()).when(jdbcRunner)
                .collect(any(), any(), any(), anyList(), anyInt(), anyInt(), any());
        assertThat(executor.processDue()).isZero();
        clock.advance(ProfileRunExecutor.LEASE.plusSeconds(1));
        assertThat(executor.processDue()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo(ProfileRunDO.STATUS_SUCCESS);
        assertThat(run.getAttemptCount()).isEqualTo(3);
        assertThat(insertedColumns).hasSize(2);
    }

    @Test
    void exhaustedExpiredRunIsFailedAndNotReclaimed() throws Exception {
        ProfileRunDO run = runs.get(RUN_ID);
        run.setStatus(ProfileRunDO.STATUS_RUNNING);
        run.setLeaseOwner("dead-node");
        run.setLeaseUntil(now().minusSeconds(1));
        run.setAttemptCount(ProfileRunExecutor.MAX_ATTEMPTS);

        assertThat(executor.processDue()).isZero();
        assertThat(run.getStatus()).isEqualTo(ProfileRunDO.STATUS_FAILED);
        assertThat(run.getFailReason()).isEqualTo(ProfileRunExecutor.EXHAUSTED_REASON);
        assertThat(run.getLeaseOwner()).isNull();
        verify(jdbcRunner, never()).collect(any(), any(), any(), anyList(), anyInt(), anyInt(), any());
    }

    @Test
    void runningRunWithLiveLeaseIsNotReclaimed() {
        ProfileRunDO run = runs.get(RUN_ID);
        run.setStatus(ProfileRunDO.STATUS_RUNNING);
        run.setLeaseOwner("live-node");
        run.setLeaseUntil(now().plusSeconds(30));
        run.setAttemptCount(1);
        assertThat(executor.processDue()).isZero();
        assertThat(executor.execute(RUN_ID)).isFalse();
        assertThat(run.getLeaseOwner()).isEqualTo("live-node");
    }

    // ========== 敏感 Top 值 ==========

    @Test
    void sensitiveColumnStoresOnlyCountsAndMaskedFlag() {
        executor.execute(RUN_ID);
        ProfileColumnDO phoneColumn = columnNamed("phone");
        assertThat(phoneColumn.getValueMasked()).isEqualTo(1);
        assertThat(phoneColumn.getMaskReason()).isEqualTo(ProfileValueMasker.REASON_METADATA);
        assertThat(phoneColumn.getMinValue()).isNull();
        assertThat(phoneColumn.getMaxValue()).isNull();
        List<ProfileValueDO> phoneValues = valuesOf(phoneColumn);
        assertThat(phoneValues).hasSize(2).allSatisfy(value -> {
            assertThat(value.getValueText()).isNull();
            assertThat(value.getValueMasked()).isEqualTo(1);
        });
        assertThat(phoneValues).extracting(ProfileValueDO::getValueCount).containsExactly(3L, 2L);
        assertThat(allPersistedText()).noneMatch(text -> text.contains("138"));

        ProfileColumnDO statusColumn = columnNamed("status");
        assertThat(statusColumn.getValueMasked()).isZero();
        assertThat(statusColumn.getMaskReason()).isNull();
        assertThat(statusColumn.getMinValue()).isEqualTo("A");
        assertThat(valuesOf(statusColumn)).extracting(ProfileValueDO::getValueText).containsExactly("A", "B");
        assertThat(statusColumn.getNullRate()).isEqualByComparingTo("0.100000");
        assertThat(statusColumn.getUniqueRate()).isEqualByComparingTo("0.022222");
    }

    @Test
    void maskerFailureStoresCountsButNoValues() {
        ReflectionTestUtils.setField(executor, "valueMasker", (ProfileValueMasker) columns -> {
            throw new IllegalStateException("classification unavailable");
        });
        when(sensitivityResolver.resolve(any(), any(), anyList())).thenReturn(Map.of(
                "phone", sensitivity("phone", false),
                "status", sensitivity("status", false)));
        executor.execute(RUN_ID);
        assertThat(runs.get(RUN_ID).getStatus()).isEqualTo(ProfileRunDO.STATUS_SUCCESS);
        assertThat(insertedColumns).hasSize(2).allSatisfy(column -> {
            assertThat(column.getValueMasked()).isEqualTo(1);
            assertThat(column.getMaskReason()).isEqualTo(ProfileValueMasker.REASON_MASK_FAILED);
            assertThat(column.getMinValue()).isNull();
            assertThat(column.getNullRate()).isNotNull();
        });
        assertThat(insertedValues).isNotEmpty().allSatisfy(value -> {
            assertThat(value.getValueText()).isNull();
            assertThat(value.getValueCount()).isPositive();
        });
    }

    @Test
    void missingOrMisshapenMaskResultFailsClosedPerColumn() {
        ReflectionTestUtils.setField(executor, "valueMasker", (ProfileValueMasker) columns -> Map.of(
                "phone", new ProfileValueMasker.MaskedValues(false, List.of("x"), null)));
        when(sensitivityResolver.resolve(any(), any(), anyList())).thenReturn(Map.of(
                "phone", sensitivity("phone", false),
                "status", sensitivity("status", false)));
        executor.execute(RUN_ID);
        assertThat(insertedColumns).allSatisfy(column -> {
            assertThat(column.getValueMasked()).isEqualTo(1);
            assertThat(column.getMaskReason()).isEqualTo(ProfileValueMasker.REASON_MASK_FAILED);
        });
        assertThat(insertedValues).allSatisfy(value -> assertThat(value.getValueText()).isNull());
    }

    @Test
    void maskerReceivesEveryColumnInOneBatch() {
        List<List<String>> batches = new ArrayList<>();
        ReflectionTestUtils.setField(executor, "valueMasker", (ProfileValueMasker) columns -> {
            batches.add(columns.stream().map(column -> column.column().columnName()).toList());
            return metadataOnlyMasker(columns);
        });
        executor.execute(RUN_ID);
        assertThat(batches).containsExactly(List.of("phone", "status"));
    }

    @Test
    void missingSensitivityIsTreatedAsSensitive() {
        when(sensitivityResolver.resolve(any(), any(), anyList())).thenReturn(Map.of());
        executor.execute(RUN_ID);
        assertThat(insertedValues).isNotEmpty().allSatisfy(value -> assertThat(value.getValueText()).isNull());
    }

    @Test
    void realMaskerOutputIsStoredButNeverRawText() {
        ReflectionTestUtils.setField(executor, "valueMasker", (ProfileValueMasker) columns -> {
            Map<String, ProfileValueMasker.MaskedValues> result = new java.util.HashMap<>();
            for (ProfileValueMasker.ColumnValues column : columns) {
                result.put(column.column().columnName(), column.column().sensitive()
                        ? new ProfileValueMasker.MaskedValues(true, column.values().stream()
                        .map(value -> value == null ? null : "***").toList(), "RULE")
                        : new ProfileValueMasker.MaskedValues(false, column.values(), null));
            }
            return result;
        });
        executor.execute(RUN_ID);
        assertThat(valuesOf(columnNamed("phone"))).extracting(ProfileValueDO::getValueText)
                .containsExactly("***", "***");
        assertThat(columnNamed("phone").getMaskReason()).isEqualTo("RULE");
        assertThat(allPersistedText()).noneMatch(text -> text.contains("138"));
    }

    // ========== 失败 ==========

    @Test
    void failureReasonNeverContainsSqlOrConnectionInfo() throws Exception {
        when(jdbcRunner.collect(any(), any(), any(), anyList(), anyInt(), anyInt(), any())).thenThrow(
                new SQLException("ERROR at SELECT phone FROM crm.customer jdbc:postgresql://10.0.0.1/db password=x",
                        "42P01"));
        executor.execute(RUN_ID);
        ProfileRunDO run = runs.get(RUN_ID);
        assertThat(run.getStatus()).isEqualTo(ProfileRunDO.STATUS_FAILED);
        assertThat(run.getFailReason()).isEqualTo("目标库查询失败（SQLState=42P01）");
        assertThat(insertedColumns).isEmpty();
        assertThat(ProfileRunExecutor.failureReason(new java.sql.SQLTimeoutException("SELECT 1")))
                .doesNotContain("SELECT").contains("60");
        assertThat(ProfileRunExecutor.failureReason(new IllegalArgumentException("bad \"name\"")))
                .doesNotContain("name");
    }

    // ========== 模型与数据 ==========

    /**
     * 与 ProfileRunMapper 中 SQL 相同的 CAS 条件的内存实现。
     */
    private void installCasModel() {
        when(runMapper.selectById(anyLong())).thenAnswer(call -> copy(runs.get(call.<Long>getArgument(0))));
        when(runMapper.claim(anyLong(), anyString(), any(), any(), anyInt())).thenAnswer(call -> {
            ProfileRunDO run = runs.get(call.<Long>getArgument(0));
            LocalDateTime now = call.getArgument(2);
            int max = call.getArgument(4);
            boolean claimable = run != null && run.getAttemptCount() < max
                    && (ProfileRunDO.STATUS_PENDING.equals(run.getStatus())
                    || (ProfileRunDO.STATUS_RUNNING.equals(run.getStatus()) && run.getLeaseUntil().isBefore(now)));
            if (!claimable) {
                return 0;
            }
            run.setStatus(ProfileRunDO.STATUS_RUNNING);
            run.setLeaseOwner(call.getArgument(1));
            run.setLeaseUntil(call.getArgument(3));
            run.setAttemptCount(run.getAttemptCount() + 1);
            if (run.getStartTime() == null) {
                run.setStartTime(now);
            }
            return 1;
        });
        when(runMapper.renew(anyLong(), anyString(), any())).thenAnswer(call -> {
            ProfileRunDO run = runs.get(call.<Long>getArgument(0));
            if (!holds(run, call.getArgument(1))) {
                return 0;
            }
            run.setLeaseUntil(call.getArgument(2));
            return 1;
        });
        when(runMapper.finish(anyLong(), anyString(), any())).thenAnswer(call -> {
            ProfileRunDO run = runs.get(call.<Long>getArgument(0));
            ProfileRunDO result = call.getArgument(2);
            if (!holds(run, call.getArgument(1))) {
                return 0;
            }
            run.setStatus(result.getStatus());
            run.setSampleRows(result.getSampleRows());
            run.setTotalRows(result.getTotalRows());
            run.setEndTime(result.getEndTime());
            run.setFailReason(result.getFailReason());
            run.setLeaseOwner(null);
            run.setLeaseUntil(null);
            return 1;
        });
        when(runMapper.selectClaimable(any(), anyInt(), anyInt())).thenAnswer(call -> {
            LocalDateTime now = call.getArgument(0);
            int max = call.getArgument(1);
            return runs.values().stream().filter(run -> run.getAttemptCount() < max
                            && (ProfileRunDO.STATUS_PENDING.equals(run.getStatus())
                            || (ProfileRunDO.STATUS_RUNNING.equals(run.getStatus())
                            && run.getLeaseUntil().isBefore(now))))
                    .map(ProfileRunExecutorTest::copy).toList();
        });
        when(runMapper.failExhausted(any(), anyInt(), anyString())).thenAnswer(call -> {
            LocalDateTime now = call.getArgument(0);
            int max = call.getArgument(1);
            int count = 0;
            for (ProfileRunDO run : runs.values()) {
                if (ProfileRunDO.STATUS_RUNNING.equals(run.getStatus()) && run.getLeaseUntil().isBefore(now)
                        && run.getAttemptCount() >= max) {
                    run.setStatus(ProfileRunDO.STATUS_FAILED);
                    run.setFailReason(call.getArgument(2));
                    run.setLeaseOwner(null);
                    run.setLeaseUntil(null);
                    count++;
                }
            }
            return count;
        });
    }

    /**
     * 只按元数据敏感依据脱敏的测试实现：敏感字段不保存文本。
     */
    private static Map<String, ProfileValueMasker.MaskedValues> metadataOnlyMasker(
            List<ProfileValueMasker.ColumnValues> columns) {
        Map<String, ProfileValueMasker.MaskedValues> result = new java.util.HashMap<>();
        for (ProfileValueMasker.ColumnValues column : columns) {
            result.put(column.column().columnName(), column.column().sensitive()
                    ? ProfileValueMasker.MaskedValues.redacted(column.values().size(), ProfileValueMasker.REASON_METADATA)
                    : new ProfileValueMasker.MaskedValues(false, column.values(), null));
        }
        return result;
    }

    private static boolean holds(ProfileRunDO run, String owner) {
        return run != null && ProfileRunDO.STATUS_RUNNING.equals(run.getStatus())
                && Objects.equals(run.getLeaseOwner(), owner);
    }

    private static ProfileRunDO copy(ProfileRunDO run) {
        return run == null ? null : cn.iocoder.yudao.framework.common.util.object.BeanUtils.toBean(run,
                ProfileRunDO.class);
    }

    private ProfileJdbcRunner.RawProfile rawProfile() {
        ProfileColumn phoneColumn = new ProfileColumn("phone", ProfileColumnKind.TEXT);
        ProfileColumn statusColumn = new ProfileColumn("status", ProfileColumnKind.TEXT);
        return new ProfileJdbcRunner.RawProfile(5000L, 100L, Map.of(
                "phone", new ProfileColumnStats(phoneColumn, 100L, 98L, "13800000000", "13899999999",
                        new BigDecimal("11"), 11L, 11L, null),
                "status", new ProfileColumnStats(statusColumn, 90L, 2L, "A", "B", new BigDecimal("1"), 1L, 1L, null)),
                Map.of("phone", List.of(new ValueCount("13800000000", 3L), new ValueCount("13811111111", 2L)),
                        "status", List.of(new ValueCount("A", 60L), new ValueCount("B", 30L))));
    }

    private static MetaColumnDO column(Long id, String name, String type, int ordinal) {
        MetaColumnDO column = new MetaColumnDO();
        column.setId(id);
        column.setColumnName(name);
        column.setDataType(type);
        column.setOrdinalPosition(ordinal);
        return column;
    }

    private static ProfileColumnSensitivity sensitivity(String column, boolean sensitive) {
        return new ProfileColumnSensitivity(TABLE_ID, null, 9L, "crm", "customer", column, sensitive, null, false,
                false, null, false);
    }

    private ProfileColumnDO columnNamed(String name) {
        return insertedColumns.stream().filter(column -> column.getColumnName().equals(name)).findFirst()
                .orElseThrow();
    }

    private List<ProfileValueDO> valuesOf(ProfileColumnDO column) {
        return insertedValues.stream().filter(value -> Objects.equals(value.getProfileColumnId(), column.getId()))
                .toList();
    }

    private List<String> allPersistedText() {
        List<String> texts = new ArrayList<>();
        insertedColumns.forEach(column -> {
            texts.add(String.valueOf(column.getMinValue()));
            texts.add(String.valueOf(column.getMaxValue()));
        });
        insertedValues.forEach(value -> texts.add(String.valueOf(value.getValueText())));
        return texts;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * 可前进的测试时钟（UTC）。
     */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

    }

}
