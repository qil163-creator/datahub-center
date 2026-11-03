package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseMonitorVO.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.*;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 数据库观测为平台内可见；只输出任务摘要，不暴露执行 SQL、凭据或敏感日志。 */
@Service
@RequiredArgsConstructor
public class DatabaseMonitorService {

    private final DatabaseMapper databaseMapper;

    private final DatabaseMonitorMapper monitorMapper;

    private final DatabaseMonitorSampleMapper sampleMapper;

    private final DatabaseAlarmRuleMapper ruleMapper;

    private final DatabaseAlarmRecordMapper alarmMapper;

    private final AccessDatabaseResolver databaseResolver;

    private final DbSwitchRuntime dbSwitchRuntime;

    private final DatabaseMonitorSampleService sampleService;

    /**
     * 获取数据库监控概览。
     *
     * @param databaseId 数据源编号
     * @return 概览
     */
    public Overview overview(Long databaseId) {
        requireDatabase(databaseId);
        var result = monitorMapper.summary(databaseId, LocalDate.now().atStartOfDay());
        result.setConnectionStatus(0);
        var latest = sampleMapper.selectOne(new LambdaQueryWrapperX<DatabaseMonitorSampleDO>()
                .eq(DatabaseMonitorSampleDO::getDatabaseId, databaseId)
                .eq(DatabaseMonitorSampleDO::getState, "COMPLETE")
                .orderByDesc(DatabaseMonitorSampleDO::getSlotTime).last("LIMIT 1"));
        if (latest != null) {
            result.setConnectionStatus(latest.getConnectionStatus());
            result.setLastCollectedTime(latest.getCollectedTime());
            result.setResponseTimeMs(latest.getResponseTimeMs());
            result.setCapacityUsed(latest.getCapacityUsed());
            result.setCollectionStatus(latest.getCollectionStatus());
            result.setCollectionMessage(latest.getCollectionMessage());
        }
        result.setPendingAlarmCount(alarmMapper.selectCount(new LambdaQueryWrapperX<DatabaseAlarmRecordDO>()
                .eq(DatabaseAlarmRecordDO::getDatabaseId, databaseId).eq(DatabaseAlarmRecordDO::getStatus, "PENDING")));
        var today = monitorMapper.sampleTrend(databaseId, LocalDate.now().atStartOfDay());
        result.setAverageResponseTimeMs(today.isEmpty() ? null : today.getFirst().getResponseTimeMs());
        return result;
    }

    /**
     * 按允许的天数区间汇总监控日志与样本，补齐每日趋势数据。
     *
     * @param databaseId 数据源编号
     * @param days 天数
     * @return 趋势
     */
    public Trend trend(Long databaseId, int days) {
        requireDatabase(databaseId);
        if (!Set.of(7, 15, 30).contains(days)) {
            throw exception(DATABASE_MONITOR_RANGE_INVALID);
        }
        LocalDate first = LocalDate.now().minusDays(days - 1L);
        Map<String, TrendPoint> logs = monitorMapper.logTrend(databaseId, first.atStartOfDay()).stream()
                .collect(Collectors.toMap(TrendPoint::getDate, Function.identity()));
        Map<String, TrendPoint> samples = monitorMapper.sampleTrend(databaseId, first.atStartOfDay()).stream()
                .collect(Collectors.toMap(TrendPoint::getDate, Function.identity()));
        List<TrendPoint> points = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            String day = first.plusDays(i).toString();
            var point = logs.getOrDefault(day, new TrendPoint());
            point.setDate(day);
            if (point.getOutputRows() == null) {
                point.setOutputRows("0");
            }
            if (point.getInputRows() == null) {
                point.setInputRows("0");
            }
            var sample = samples.get(day);
            if (sample != null) {
                point.setResponseTimeMs(sample.getResponseTimeMs());
                point.setOnlineRate(sample.getOnlineRate());
            }
            points.add(point);
        }
        var result = new Trend();
        result.setDays(days);
        result.setPoints(points);
        return result;
    }

    /**
     * 获取数据库关联任务读写日志，不包含无冻结绑定证据的旧同步记录。
     *
     * @param req 请求参数
     * @return 数据库关联任务读写日志，不包含无冻结绑定证据的旧同步记录结果
     */
    public PageResult<ReadWriteLog> logs(PageReq req) {
        requireDatabase(req.getDatabaseId());
        int size = Math.min(100, Math.max(1, req.getPageSize()));
        return new PageResult<>(monitorMapper.logs(req.getDatabaseId(), size,
                (long) (req.getPageNo() - 1) * size), monitorMapper.logCount(req.getDatabaseId()));
    }

    /** 无平台事务、无数据库行锁。分钟槽唯一且租约令牌保证重试幂等。 */
    public Overview collect(Long databaseId) {
        collectSample(databaseId);
        return overview(databaseId);
    }

    /**
     * 收集样本。
     *
     * @param databaseId 数据源编号
     */
    public void collectSample(Long databaseId) {
        DatabaseDO database = requireDatabase(databaseId);
        if (!Objects.equals(database.getStatus(), 0)) {
            throw exception(DATABASE_UNAVAILABLE);
        }
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        Long id = monitorMapper.claim(databaseId, now.truncatedTo(ChronoUnit.MINUTES), now, token);
        if (id == null) {
            return;
        }
        int status = 0;
        Long response = null;
        String capacity = null;
        String collectionStatus = "SUCCESS";
        String collectionMessage = "采集成功";
        try {
            var connection = databaseResolver.resolve(database);
            var metadata = dbSwitchRuntime.jdbcMetadata(connection.getProductType());
            long started = System.nanoTime();
            metadata.probeConnection(connection.getJdbcUrl(), connection.getUsername(), connection.getPassword(),
                    10_000);
            response = Math.max(0L, (System.nanoTime() - started) / 1_000_000);
            status = 1;
            // 可选容量采集在探活成功后独立降级；不将无权限或不支持误报为连接离线。
            try {
                capacity = Long.toString(metadata.queryDatabaseUsedBytes(connection.getJdbcUrl(),
                        connection.getUsername(), connection.getPassword(), 5_000));
            } catch (Exception ignored) { /* 不支持、权限不足或采集超时均保持未知。 */ }
        } catch (Exception failure) {
            // 禁止持久化驱动异常原文；异常可能含用户名、连接串或密码。
            collectionStatus = classifyFailure(failure);
            if ("OFFLINE".equals(collectionStatus)) {
                status = 2;
                collectionStatus = "FAILED";
                collectionMessage = "连接建立或网络通信失败";
            } else if ("UNSUPPORTED".equals(collectionStatus)) {
                collectionMessage = "当前产品或驱动不支持此采集操作";
            } else {
                collectionMessage = "采集失败，请检查驱动配置和数据库账号权限";
            }
        }
        sampleService.complete(databaseId, id, token, database.getUpdateTime(), now, status, response, capacity,
                collectionStatus, collectionMessage);
    }

    /** 每轮最多 100 个候选，按最久未采样优先；30 秒后不再发起新连接。 */
    public int collectPlatform() {
        long deadline = System.nanoTime() + 30_000_000_000L;
        int count = 0;
        int failures = 0;
        for (Long id : monitorMapper.candidates(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES))) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {
                collectSample(id);
                count++;
            } catch (Exception ignored) {
                failures++;
            }
        }
        monitorMapper.cleanSamples(LocalDateTime.now().minusDays(35));
        if (failures > 0) {
            throw new IllegalStateException("数据库采样持久化失败数量：" + failures);
        }
        return count;
    }

    /**
     * 获取数据库告警规则分页。
     *
     * @param req 请求参数
     * @return 数据库告警规则分页结果
     */
    public PageResult<Rule> rules(PageReq req) {
        requireDatabase(req.getDatabaseId());
        req.setPageSize(Math.min(100, Math.max(1, req.getPageSize())));
        var page = ruleMapper.selectPage(req, new LambdaQueryWrapperX<DatabaseAlarmRuleDO>()
                .eq(DatabaseAlarmRuleDO::getDatabaseId, req.getDatabaseId()).orderByDesc(DatabaseAlarmRuleDO::getId));
        return new PageResult<>(BeanUtils.toBean(page.getList(), Rule.class), page.getTotal());
    }

    /**
     * 获取数据库告警规则详情。
     *
     * @param databaseId 数据源编号
     * @param id 记录编号
     * @return 规则
     */
    public Rule rule(Long databaseId, Long id) {
        requireDatabase(databaseId);
        return BeanUtils.toBean(requireRule(databaseId, id), Rule.class);
    }

    /**
     * 创建数据库告警规则。
     *
     * @param req 请求参数
     * @return 生成或保存的记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createRule(RuleSave req) {
        lockDatabase(req.getDatabaseId());
        validateRule(req);
        var rule = BeanUtils.toBean(req, DatabaseAlarmRuleDO.class);
        rule.setId(null);
        ruleMapper.insert(rule);
        return rule.getId();
    }

    /**
     * 更新数据库告警规则。
     *
     * @param req 请求参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateRule(RuleSave req) {
        lockDatabase(req.getDatabaseId());
        requireRule(req.getDatabaseId(), req.getId());
        validateRule(req);
        ruleMapper.updateById(BeanUtils.toBean(req, DatabaseAlarmRuleDO.class));
    }

    /**
     * 锁定数据源并更新所属监控规则的启用状态。
     *
     * @param req 请求参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void statusRule(RuleStatus req) {
        lockDatabase(req.getDatabaseId());
        var rule = requireRule(req.getDatabaseId(), req.getId());
        rule.setStatus(req.getStatus());
        ruleMapper.updateById(rule);
    }

    /**
     * 删除数据库告警规则，保留历史告警审计记录。
     *
     * @param databaseId 数据源编号
     * @param id 记录编号
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(Long databaseId, Long id) {
        lockDatabase(databaseId);
        requireRule(databaseId, id);
        ruleMapper.deleteById(id);
    }

    /**
     * 获取数据库告警分页。
     *
     * @param req 请求参数
     * @return 数据库告警分页结果
     */
    public PageResult<Alarm> alarms(PageReq req) {
        requireDatabase(req.getDatabaseId());
        req.setPageSize(Math.min(100, Math.max(1, req.getPageSize())));
        var page = alarmMapper.selectPage(req, new LambdaQueryWrapperX<DatabaseAlarmRecordDO>()
                .eq(DatabaseAlarmRecordDO::getDatabaseId, req.getDatabaseId())
                .orderByDesc(DatabaseAlarmRecordDO::getLastTime).orderByDesc(DatabaseAlarmRecordDO::getId));
        return new PageResult<>(BeanUtils.toBean(page.getList(), Alarm.class), page.getTotal());
    }

    /**
     * 获取数据库告警详情。
     *
     * @param databaseId 数据源编号
     * @param id 记录编号
     * @return 告警
     */
    public Alarm alarm(Long databaseId, Long id) {
        requireDatabase(databaseId);
        return BeanUtils.toBean(requireAlarm(databaseId, id), Alarm.class);
    }

    /**
     * 处理告警。
     *
     * @param req 请求参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleAlarm(AlarmHandle req) {
        lockDatabase(req.getDatabaseId());
        var alarm = requireAlarm(req.getDatabaseId(), req.getId());
        if (!"PENDING".equals(alarm.getStatus())) {
            throw exception(DATABASE_MONITOR_ALARM_CONFLICT);
        }
        Long user = SecurityFrameworkUtils.getLoginUserId();
        if (user == null) {
            throw exception(OPERATION_PERMISSION_DENIED);
        }
        alarm.setStatus("HANDLE".equals(req.getAction()) ? "HANDLED" : "IGNORED");
        alarm.setHandledBy(user.toString());
        alarm.setHandledTime(LocalDateTime.now());
        alarm.setHandleRemark(req.getRemark());
        alarmMapper.updateById(alarm);
    }

    /**
     * 校验规则。
     *
     * @param req 请求参数
     */
    static void validateRule(RuleSave req) {
        BigDecimal threshold;
        try {
            threshold = new BigDecimal(req.getThreshold());
        } catch (RuntimeException ex) {
            throw exception(DATABASE_MONITOR_RULE_INVALID);
        }
        if ("CONNECTION_STATUS".equals(req.getMetric())) {
            if (!"EQ".equals(req.getOperator()) || threshold.compareTo(BigDecimal.ZERO) != 0) {
                throw exception(DATABASE_MONITOR_RULE_INVALID);
            }
        } else if (!"RESPONSE_TIME".equals(req.getMetric()) || threshold.signum() <= 0) {
            throw exception(DATABASE_MONITOR_RULE_INVALID);
        }
    }

    /**
     * 获取数据源监控的分类失败。
     *
     * @param failure 失败原因
     * @return 数据源监控的分类失败文本
     */
    static String classifyFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean offline = false;
        while (failure != null && seen.add(failure)) {
            if (failure instanceof UnsupportedOperationException || failure instanceof SQLFeatureNotSupportedException) {
                return "UNSUPPORTED";
            }
            if (failure instanceof ConnectException || failure instanceof SocketTimeoutException
                    || (failure instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState()
                    .startsWith("08"))) {
                offline = true;
            }
            failure = failure.getCause();
        }
        return offline ? "OFFLINE" : "FAILED";
    }

    /**
     * 校验数据源。
     *
     * @param databaseId 数据源编号
     * @return 数据源
     */
    private DatabaseDO requireDatabase(Long databaseId) {

        DatabaseDO database = databaseMapper.selectById(databaseId);
        if (database == null) {
            throw exception(DATABASE_NOT_EXISTS);
        }
        return database;
    }

    /**
     * 锁定数据源监控的数据源。
     *
     * @param databaseId 数据源编号
     */
    private void lockDatabase(Long databaseId) {
        if (monitorMapper.lockDatabase(databaseId) == null) {
            throw exception(DATABASE_NOT_EXISTS);
        }
    }

    /**
     * 校验规则。
     *
     * @param databaseId 数据源编号
     * @param id 记录编号
     * @return 数据源告警规则
     */
    private DatabaseAlarmRuleDO requireRule(Long databaseId, Long id) {
        var value = id == null ? null : ruleMapper.selectById(id);
        if (value == null || !Objects.equals(value.getDatabaseId(), databaseId)) {
            throw exception(DATABASE_MONITOR_RULE_NOT_EXISTS);
        }
        return value;
    }

    /**
     * 校验告警。
     *
     * @param databaseId 数据源编号
     * @param id 记录编号
     * @return 数据源告警记录
     */
    private DatabaseAlarmRecordDO requireAlarm(Long databaseId, Long id) {
        var value = id == null ? null : alarmMapper.selectById(id);
        if (value == null || !Objects.equals(value.getDatabaseId(), databaseId)) {
            throw exception(DATABASE_MONITOR_ALARM_NOT_EXISTS);
        }
        return value;
    }

}
