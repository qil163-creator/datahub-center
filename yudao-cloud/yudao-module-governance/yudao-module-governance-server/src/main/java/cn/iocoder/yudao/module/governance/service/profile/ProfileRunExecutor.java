package cn.iocoder.yudao.module.governance.service.profile;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileColumn;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileColumnKind;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileColumnStats;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileRequest;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileSqlGenerator;
import cn.iocoder.yudao.framework.quality.core.profile.ProfileTopValuesQuery;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileRunDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.profile.ProfileValueDO;
import cn.iocoder.yudao.module.governance.dal.mysql.profile.ProfileRunMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PROFILE_DATASOURCE_MISSING;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.PROFILE_PRODUCT_UNSUPPORTED;

/**
 * 探查执行编排：CAS 领取 → 事务外 JDBC 采集（每条查询后续租）→ 脱敏 → 以租约持有者身份一次性落库。
 *
 * <ul>
 *     <li>领取条件：PENDING，或 RUNNING 且租约过期、领取次数 &lt; {@link #MAX_ATTEMPTS}（失联恢复）；</li>
 *     <li>租约 {@link #LEASE}，每条查询（≤ 60 秒）后续租；续租失败立即放弃，不写任何结果；</li>
 *     <li>次数用尽且租约过期的记录由 {@link #processDue()} 标记 FAILED，释放单表并发名额；</li>
 *     <li>失败原因只含分类信息与 SQLState，不含 SQL 文本、取值或连接信息。</li>
 * </ul>
 *
 * @author Li
 */
@Slf4j
@Component
public class ProfileRunExecutor {

    /** 执行租约时长 */
    public static final Duration LEASE = Duration.ofMinutes(3);
    /** 最大领取次数 */
    public static final int MAX_ATTEMPTS = 3;
    /** 每次扫描最多处理的记录数 */
    public static final int SCAN_BATCH = 5;
    /** 次数用尽时的失败原因 */
    public static final String EXHAUSTED_REASON = "执行租约多次失效（节点失联或超时），已停止重试";

    @Resource
    private ProfileRunMapper runMapper;

    @Resource
    private ProfileTargetLoader targetLoader;

    @Resource
    private MasterDatabaseResolver databaseResolver;

    @Resource
    private ProfileSensitivityResolver sensitivityResolver;

    @Resource
    private ProfileValueMasker valueMasker;

    @Resource
    private ProfileJdbcRunner jdbcRunner;

    @Resource
    private ProfileResultWriter resultWriter;

    private Clock clock = Clock.systemDefaultZone();

    /** 发起后立即执行的有界线程池；满载时留给定时任务领取，不阻塞请求线程 */
    private final ThreadPoolExecutor dispatchPool = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), new NamedThreadFactory(), new ThreadPoolExecutor.AbortPolicy());

    /**
     * 测试注入时钟。
     */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 提交后异步尝试执行；池满时静默放弃，由 profileRunJob 兜底领取。
     */
    public void dispatch(Long runId) {
        try {
            dispatchPool.execute(() -> execute(runId));
        } catch (RejectedExecutionException ex) {
            log.info("[dispatch][探查记录({}) 等待定时任务领取]", runId);
        }
    }

    /**
     * 定时任务入口：先结束次数用尽的失联记录，再按编号顺序领取并执行可执行记录。返回本次实际执行的记录数。
     */
    public int processDue() {
        LocalDateTime now = now();
        int exhausted = runMapper.failExhausted(now, MAX_ATTEMPTS, EXHAUSTED_REASON);
        if (exhausted > 0) {
            log.warn("[processDue][{} 条探查记录租约多次失效，已标记失败]", exhausted);
        }
        int executed = 0;
        for (ProfileRunDO run : runMapper.selectClaimable(now, MAX_ATTEMPTS, SCAN_BATCH)) {
            if (execute(run.getId())) {
                executed++;
            }
        }
        return executed;
    }

    /**
     * 领取并执行一条记录；未领取到（已被他人领取或状态已变化）返回 false。
     */
    public boolean execute(Long runId) {
        String owner = UUID.randomUUID().toString();
        LocalDateTime now = now();
        if (runMapper.claim(runId, owner, now, now.plus(LEASE), MAX_ATTEMPTS) != 1) {
            return false;
        }
        try {
            runClaimed(runId, owner);
        } catch (ProfileLeaseLostException ex) {
            log.warn("[execute][探查记录({}) 租约已丢失，放弃本次执行]", runId);
        } catch (Exception ex) {
            String reason = failureReason(ex);
            log.warn("[execute][探查记录({}) 执行失败] {}", runId, reason);
            if (!resultWriter.fail(runId, owner, reason, now())) {
                log.warn("[execute][探查记录({}) 租约已丢失，失败结果未写入]", runId);
            }
        }
        return true;
    }

    /**
     * 已持有租约时的完整执行流程。
     */
    private void runClaimed(Long runId, String owner) throws SQLException {
        ProfileRunDO run = runMapper.selectById(runId);
        ProfileTargetLoader.ProfileTarget target = targetLoader.load(run.getMetaTableId());
        MasterDatabaseResolver.ConnectionInfo conn = databaseResolver.resolve(target.table().getDatasourceId(),
                PROFILE_DATASOURCE_MISSING);
        ProductTypeEnum product = conn.getProductType();
        if (!ProfileSqlGenerator.supports(product)) {
            throw exception(PROFILE_PRODUCT_UNSUPPORTED, String.valueOf(product));
        }
        String schema = target.schemaName(product);
        List<ProfileColumn> columns = new ArrayList<>(target.columns().size());
        for (MetaColumnDO column : target.columns()) {
            columns.add(new ProfileColumn(column.getColumnName(),
                    ProfileColumnKind.fromTypeName(product, column.getDataType())));
        }
        renew(runId, owner);
        ProfileJdbcRunner.RawProfile raw = jdbcRunner.collect(conn, schema, target.table().getTableName(), columns,
                run.getSampleLimit(), ProfileRequest.DEFAULT_TOP_N, () -> renew(runId, owner));
        Map<String, ProfileColumnSensitivity> sensitivity = sensitivityResolver.resolve(target.table(), schema,
                target.columns());
        // 先在事务外统一脱敏，再进入短事务落库
        List<Integer> profiled = new ArrayList<>(columns.size());
        List<ProfileValueMasker.ColumnValues> inputs = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            ProfileColumnStats stats = raw.stats().get(columns.get(i).name());
            if (stats == null) {
                continue;
            }
            MetaColumnDO meta = target.columns().get(i);
            ProfileColumnSensitivity columnSensitivity = sensitivity.get(
                    ProfileSensitivityResolver.lower(meta.getColumnName()));
            if (columnSensitivity == null) {
                columnSensitivity = new ProfileColumnSensitivity(target.table().getId(), meta.getId(),
                        target.table().getDatasourceId(), schema, target.table().getTableName(),
                        meta.getColumnName(), false, null, false, false, null, true);
            }
            profiled.add(i);
            inputs.add(new ProfileValueMasker.ColumnValues(columnSensitivity,
                    rawValues(stats, raw.topValues().getOrDefault(columns.get(i).name(), List.of()))));
        }
        Map<String, ProfileValueMasker.MaskedValues> masked = safeMask(inputs);
        List<ProfileResultWriter.ColumnResult> results = new ArrayList<>(profiled.size());
        for (int i : profiled) {
            MetaColumnDO meta = target.columns().get(i);
            results.add(buildColumn(meta, raw.stats().get(columns.get(i).name()), raw.sampledRows(),
                    raw.topValues().getOrDefault(columns.get(i).name(), List.of()), masked.get(meta.getColumnName())));
        }
        if (!resultWriter.complete(runId, owner, raw.sampledRows(), raw.totalRows(), now(), results)) {
            throw new ProfileLeaseLostException(runId);
        }
    }

    /**
     * 单字段待脱敏原始取值：{@code [min, max, top1..topN]}。
     */
    private static List<String> rawValues(ProfileColumnStats stats, List<ProfileTopValuesQuery.ValueCount> top) {
        List<String> raw = new ArrayList<>(top.size() + 2);
        raw.add(stats.minValue());
        raw.add(stats.maxValue());
        top.forEach(value -> raw.add(value.value()));
        return java.util.Collections.unmodifiableList(raw);
    }

    /**
     * 组装单字段指标与 Top 值；取值来自已脱敏结果，结果缺失或不完整时不保存任何取值（失败关闭）。
     */
    ProfileResultWriter.ColumnResult buildColumn(MetaColumnDO meta, ProfileColumnStats stats, long sampledRows,
            List<ProfileTopValuesQuery.ValueCount> top, ProfileValueMasker.MaskedValues maskedValues) {
        List<String> raw = rawValues(stats, top);
        ProfileValueMasker.MaskedValues masked = normalize(raw, maskedValues);

        ProfileColumnDO column = new ProfileColumnDO();
        column.setMetaColumnId(meta.getId());
        column.setColumnName(meta.getColumnName());
        column.setDataType(meta.getDataType());
        column.setColumnKind(stats.column().kind().name());
        column.setOrdinalPosition(meta.getOrdinalPosition());
        long nonNull = Math.min(stats.nonNullCount(), sampledRows);
        column.setNonNullCount(nonNull);
        column.setNullCount(sampledRows - nonNull);
        BigDecimal nullRate = ProfileConvert.rate(sampledRows - nonNull, sampledRows);
        column.setNullRate(nullRate == null ? BigDecimal.ZERO.setScale(ProfileConvert.RATE_SCALE) : nullRate);
        column.setDistinctCount(stats.distinctCount());
        column.setUniqueRate(stats.distinctCount() == null ? null : ProfileConvert.rate(stats.distinctCount(), nonNull));
        column.setMinValue(masked.values().get(0));
        column.setMaxValue(masked.values().get(1));
        column.setValueMasked(masked.masked() ? 1 : 0);
        column.setMaskReason(masked.masked() ? masked.reason() : null);
        column.setAvgLength(stats.avgLength() == null ? null : stats.avgLength().setScale(4, RoundingMode.HALF_UP));
        column.setMinLength(stats.minLength());
        column.setMaxLength(stats.maxLength());
        column.setAvgValue(stats.avgValue() == null ? null : stats.avgValue().setScale(6, RoundingMode.HALF_UP));

        List<ProfileValueDO> values = new ArrayList<>(top.size());
        for (int i = 0; i < top.size(); i++) {
            String text = masked.values().get(i + 2);
            ProfileValueDO value = new ProfileValueDO();
            value.setRankNo(i + 1);
            value.setValueCount(top.get(i).count());
            // 未脱敏却拿不到文本时也按已脱敏处理，满足库表约束且不伪造取值
            boolean valueMasked = masked.masked() || text == null;
            value.setValueMasked(valueMasked ? 1 : 0);
            value.setValueText(text);
            values.add(value);
        }
        return new ProfileResultWriter.ColumnResult(column, values);
    }

    /**
     * 校验单字段脱敏结果：缺失或长度不符 → 全部不保存；未脱敏 → 以原值为准；已脱敏 → 原值为空的位置保持为空。
     */
    private static ProfileValueMasker.MaskedValues normalize(List<String> raw,
            ProfileValueMasker.MaskedValues result) {
        if (result == null || result.values() == null || result.values().size() != raw.size()) {
            return ProfileValueMasker.MaskedValues.redacted(raw.size(), ProfileValueMasker.REASON_MASK_FAILED);
        }
        if (!result.masked()) {
            return new ProfileValueMasker.MaskedValues(false, raw, null);
        }
        List<String> values = new ArrayList<>(result.values());
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i) == null) {
                values.set(i, null);
            }
        }
        String reason = result.reason() == null ? ProfileValueMasker.REASON_MASK_FAILED : result.reason();
        return new ProfileValueMasker.MaskedValues(true, values, reason);
    }

    /**
     * 调用脱敏扩展点；异常或空结果时返回空映射，由 {@link #normalize} 对每个字段按失败关闭处理。
     */
    private Map<String, ProfileValueMasker.MaskedValues> safeMask(List<ProfileValueMasker.ColumnValues> inputs) {
        if (inputs.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, ProfileValueMasker.MaskedValues> result = valueMasker.mask(inputs);
            return result == null ? Map.of() : result;
        } catch (RuntimeException ex) {
            log.warn("[safeMask][{} 个字段脱敏失败，全部不保存取值] {}", inputs.size(), ex.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * 续租；租约已丢失时抛出 {@link ProfileLeaseLostException}。
     */
    private void renew(Long runId, String owner) {
        if (runMapper.renew(runId, owner, now().plus(LEASE)) != 1) {
            throw new ProfileLeaseLostException(runId);
        }
    }

    /**
     * 将异常映射为可展示的失败原因，不暴露 SQL 文本或连接信息。
     */
    static String failureReason(Throwable ex) {
        if (ex instanceof ServiceException service) {
            return service.getMessage();
        }
        if (ex instanceof SQLTimeoutException) {
            return "目标库查询超时（单条 " + ProfileJdbcRunner.QUERY_TIMEOUT_SECONDS + " 秒）";
        }
        if (ex instanceof SQLException sql) {
            return "目标库查询失败（SQLState=" + (sql.getSQLState() == null ? "未知" : sql.getSQLState()) + "）";
        }
        if (ex instanceof UnsupportedOperationException) {
            return "数据探查暂不支持该数据库类型";
        }
        if (ex instanceof IllegalArgumentException) {
            return "表名或字段名不符合该数据库的安全引用规则，已拒绝执行";
        }
        return "探查执行失败（" + ex.getClass().getSimpleName() + "）";
    }

    /**
     * 当前时间。
     */
    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 关闭立即执行线程池；未完成的记录租约过期后由定时任务接管。
     */
    @PreDestroy
    public void shutdown() {
        dispatchPool.shutdownNow();
    }

    /**
     * 探查线程命名。
     */
    private static final class NamedThreadFactory implements java.util.concurrent.ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        /**
         * 创建守护线程。
         */
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "governance-profile-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }

    }

}
