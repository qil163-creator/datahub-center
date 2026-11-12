package cn.iocoder.yudao.module.governance.service.security;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyAccessAlertHandleReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyAccessAlertPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.security.vo.SafetyAccessAlertRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.security.SafetyAccessAlertDO;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyAccessAlertMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.security.SafetyAccessUserStat;
import cn.iocoder.yudao.module.governance.framework.security.SafetyAccessAlertProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.ACCESS_ALERT_NOT_EXISTS;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.ACCESS_ALERT_STATE_CHANGED;

/**
 * 异常访问识别：按用户、按已结束的统计窗口汇总敏感数据访问审计，套用阈值规则生成告警。
 * <p>
 * 幂等：告警以“规则 + 用户 + 窗口开始”去重（数据库唯一约束），任务重跑、多实例并发都不会重复告警。
 * 窗口只取已经结束的时间段，延迟写入的审计在下一次补跑同一窗口时仍会被统计（补跑由任务参数指定）。
 * 数据范围：平台级安全审计，按权限码控制，平台内全可见。
 */
@Slf4j
@Service
public class SafetyAccessAlertService {

    @Resource
    private SafetyAccessAlertMapper alertMapper;
    @Resource
    private SafetyAccessAlertProperties properties;

    /**
     * 识别 {@code now} 之前最近一个完整窗口。
     *
     * @return 新生成的告警数
     */
    public int detect(LocalDateTime now) {
        int window = Math.max(5, properties.getWindowMinutes());
        LocalDateTime dayStart = now.truncatedTo(ChronoUnit.DAYS);
        long minutes = ChronoUnit.MINUTES.between(dayStart, now);
        LocalDateTime to = dayStart.plusMinutes(minutes - minutes % window);
        return detectWindow(to.minusMinutes(window), to);
    }

    /**
     * 识别指定窗口（供补跑）。
     */
    public int detectWindow(LocalDateTime from, LocalDateTime to) {
        List<SafetyAccessUserStat> stats = alertMapper.selectUserStats(from, to, properties.getOffHoursStart(),
                properties.getOffHoursEnd());
        int created = 0;
        for (SafetyAccessUserStat stat : stats) {
            for (SafetyAccessAlertDO alert : evaluate(stat, from, to)) {
                try {
                    alertMapper.insert(alert);
                    created++;
                } catch (DuplicateKeyException alreadyRaised) {
                    // 同一窗口已告警：幂等跳过。
                }
            }
        }
        if (created > 0) {
            log.info("[detectWindow][识别到异常访问 from={} to={} created={}]", from, to, created);
        }
        return created;
    }

    /**
     * 对单个用户的统计套用阈值规则。
     */
    List<SafetyAccessAlertDO> evaluate(SafetyAccessUserStat stat, LocalDateTime from, LocalDateTime to) {
        List<SafetyAccessAlertDO> alerts = new ArrayList<>();
        check(alerts, stat, SafetyAccessAlertDO.RULE_VOLUME, stat.getTotal(), properties.getVolumeThreshold(), from, to);
        check(alerts, stat, SafetyAccessAlertDO.RULE_PLAINTEXT, stat.getPlaintext(), properties.getPlaintextThreshold(),
                from, to);
        check(alerts, stat, SafetyAccessAlertDO.RULE_OFF_HOURS, stat.getOffHours(), properties.getOffHoursThreshold(),
                from, to);
        check(alerts, stat, SafetyAccessAlertDO.RULE_TABLE_SPREAD, stat.getTables(),
                properties.getTableSpreadThreshold(), from, to);
        return alerts;
    }

    /**
     * 分页。
     */
    public PageResult<SafetyAccessAlertRespVO> page(SafetyAccessAlertPageReqVO req) {
        return BeanUtils.toBean(alertMapper.selectPage(req), SafetyAccessAlertRespVO.class);
    }

    /**
     * 处理待处理告警；按修订号 CAS，已被他人处理时拒绝。
     */
    public void handle(SafetyAccessAlertHandleReqVO req) {
        if (alertMapper.selectById(req.getId()) == null) {
            throw exception(ACCESS_ALERT_NOT_EXISTS);
        }
        if (alertMapper.handle(req.getId(), req.getExpectedRevision(), req.getStatus(),
                SecurityFrameworkUtils.getLoginUserId(), req.getRemark().trim(),
                LocalDateTime.now(ZoneId.systemDefault())) != 1) {
            throw exception(ACCESS_ALERT_STATE_CHANGED);
        }
    }

    // ==================== 内部 ====================

    /**
     * 统计值达到阈值时生成告警；阈值小于 1 视为关闭该规则。
     */
    private static void check(List<SafetyAccessAlertDO> alerts, SafetyAccessUserStat stat, String rule, Integer value,
                              int threshold, LocalDateTime from, LocalDateTime to) {
        if (threshold < 1 || value == null || value < threshold) {
            return;
        }
        SafetyAccessAlertDO alert = new SafetyAccessAlertDO();
        alert.setDedupeKey(rule + ":" + stat.getUserId() + ":" + from);
        alert.setRuleCode(rule);
        alert.setUserId(stat.getUserId());
        alert.setUserName(StrUtil.maxLength(StrUtil.nullToEmpty(stat.getUserName()), 60));
        alert.setWindowStart(from);
        alert.setWindowEnd(to);
        alert.setMetricValue(value);
        alert.setThreshold(threshold);
        alert.setStatus(SafetyAccessAlertDO.STATUS_OPEN);
        alert.setRevision(0);
        alerts.add(alert);
    }
}
