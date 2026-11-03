package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/** 外库探测结束后才进入短事务；数据库行锁只序列化样本发布、规则变更和告警处置。 */
@Service
@RequiredArgsConstructor
public class DatabaseMonitorSampleService {

    private final DatabaseMonitorMapper monitorMapper;

    private final DatabaseMapper databaseMapper;

    private final DatabaseMonitorSampleMapper sampleMapper;

    private final DatabaseAlarmRuleMapper ruleMapper;

    private final DatabaseAlarmRecordMapper alarmMapper;

    /**
     * 完成数据源监控样本处理。
     *
     * @param databaseId 数据源编号
     * @param sampleId 样本编号
     * @param token 当前执行或租约令牌
     * @param revision 修订号
     * @param collected 已收集
     * @param status 目标状态
     * @param response 响应对象
     * @param capacity 容量
     * @param collectionStatus 集合状态
     * @param collectionMessage 集合消息
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long databaseId, Long sampleId, String token, LocalDateTime revision,
            LocalDateTime collected, int status, Long response, String capacity,
            String collectionStatus, String collectionMessage) {

        if (monitorMapper.lockDatabase(databaseId) == null) {
            return;
        }
        DatabaseDO current = databaseMapper.selectById(databaseId);
        // 修改连接配置或停用后，旧配置的异步探测不能发布到新配置。
        if (!Objects.equals(current.getUpdateTime(), revision) || !Objects.equals(current.getStatus(), 0)) {
            sampleMapper.update(null, Wrappers.lambdaUpdate(DatabaseMonitorSampleDO.class)
                    .set(DatabaseMonitorSampleDO::getState, "DISCARDED")
                    .eq(DatabaseMonitorSampleDO::getId, sampleId).eq(DatabaseMonitorSampleDO::getLeaseToken, token)
                    .eq(DatabaseMonitorSampleDO::getState, "COLLECTING"));
            return;
        }
        if (monitorMapper.complete(databaseId, sampleId, token, collected, status, response, capacity,
                collectionStatus, collectionMessage) != 1) {
            return;
        }
        // 延迟样本保留历史，但不能倒退当前状态或重新触发旧事件。
        DatabaseMonitorSampleDO latest = sampleMapper.selectOne(new LambdaQueryWrapperX<DatabaseMonitorSampleDO>()
                .eq(DatabaseMonitorSampleDO::getDatabaseId, databaseId)
                .eq(DatabaseMonitorSampleDO::getState, "COMPLETE")
                .orderByDesc(DatabaseMonitorSampleDO::getSlotTime).last("LIMIT 1"));
        if (latest == null || !Objects.equals(latest.getId(), sampleId)) {
            return;
        }
        for (DatabaseAlarmRuleDO rule : ruleMapper.selectList(new LambdaQueryWrapperX<DatabaseAlarmRuleDO>()
                .eq(DatabaseAlarmRuleDO::getDatabaseId, databaseId).eq(DatabaseAlarmRuleDO::getStatus, 0))) {
            String value = "CONNECTION_STATUS".equals(rule.getMetric()) ? (status == 1 ? "1" : status == 2 ? "0" : null)
                    : response == null ? null : response.toString();
            if (value == null || !matches(rule.getOperator(), rule.getThreshold(), value)) {
                continue;
            }
            DatabaseAlarmRecordDO pending = alarmMapper.selectOne(new LambdaQueryWrapperX<DatabaseAlarmRecordDO>()
                    .eq(DatabaseAlarmRecordDO::getDatabaseId, databaseId)
                    .eq(DatabaseAlarmRecordDO::getRuleId, rule.getId())
                    .eq(DatabaseAlarmRecordDO::getStatus, "PENDING"));
            if (pending != null) {
                // 同一规则编辑后，当前告警展示最近一次触发时完整快照，避免指标单位与值错配。
                pending.setRuleName(rule.getName());
                pending.setDescription(rule.getDescription());
                pending.setMetric(rule.getMetric());
                pending.setOperator(rule.getOperator());
                pending.setThreshold(rule.getThreshold());
                pending.setLevel(rule.getLevel());
                pending.setCurrentValue(value);
                pending.setLastTime(collected);
                pending.setOccurrences(Math.min(Integer.MAX_VALUE - 1, pending.getOccurrences()) + 1);
                alarmMapper.updateById(pending);
            } else {
                var alarm = new DatabaseAlarmRecordDO();
                alarm.setDatabaseId(databaseId);
                alarm.setRuleId(rule.getId());
                alarm.setRuleName(rule.getName());
                alarm.setDescription(rule.getDescription());
                alarm.setMetric(rule.getMetric());
                alarm.setOperator(rule.getOperator());
                alarm.setThreshold(rule.getThreshold());
                alarm.setCurrentValue(value);
                alarm.setLevel(rule.getLevel());
                alarm.setStatus("PENDING");
                alarm.setFirstTime(collected);
                alarm.setLastTime(collected);
                alarm.setOccurrences(1);
                alarmMapper.insert(alarm);
            }
        }
    }

    /**
     * 判断给定值是否与预期结果匹配。
     *
     * @param operator 操作人
     * @param threshold 阈值
     * @param value 待处理的值
     * @return 判断结果
     */
    static boolean matches(String operator, String threshold, String value) {
        int compared = new BigDecimal(value).compareTo(new BigDecimal(threshold));
        return switch (operator) {
            case "EQ" -> compared == 0;
            case "GT" -> compared > 0;
            case "GTE" -> compared >= 0;
            case "LT" -> compared < 0;
            case "LTE" -> compared <= 0;
            default -> false;
        };
    }
}
