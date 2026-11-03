package cn.iocoder.yudao.module.governance.service.dashboard;

import cn.hutool.core.util.NumberUtil;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.dashboard.vo.GovernanceSafetySummaryRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyFieldTagDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyIdentifyRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.level.SafetyLevelDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskLogDO;
import cn.iocoder.yudao.module.governance.dal.mysql.accesslog.SafetyAccessLogMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyFieldTagMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyIdentifyRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskLogMapper;
import cn.iocoder.yudao.module.governance.service.whitelist.SafetyWhitelistService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;

/**
 * 治理总览与首页所需的安全统计。
 *
 * @author Li
 */
@Service
public class GovernanceSafetySummaryServiceImpl implements GovernanceSafetySummaryService {

    private static final int TREND_DAYS = 30;

    @Resource
    private MetaColumnMapper metaColumnMapper;

    @Resource
    private SafetyFieldTagMapper tagMapper;

    @Resource
    private SafetyIdentifyRuleMapper identifyRuleMapper;

    @Resource
    private SafetyAccessLogMapper accessLogMapper;

    @Resource
    private SafetyLevelMapper levelMapper;

    @Resource
    private SafetyStaticTaskLogMapper staticTaskLogMapper;

    @Resource
    private SafetyWhitelistService whitelistService;

    /**
     * 获得治理总览所需的安全统计
     */
    @Override
    public GovernanceSafetySummaryRespVO getSummary() {
        GovernanceSafetySummaryRespVO vo = new GovernanceSafetySummaryRespVO();
        LocalDateTime since = LocalDateTime.now().minusDays(TREND_DAYS);
        // 1. 打标覆盖
        vo.setMetaColumnCount(metaColumnMapper.selectCount());
        vo.setSensitiveColumnCount(metaColumnMapper.selectCount(MetaColumnDO::getSensitiveFlag, 1));
        vo.setPendingTagCount(tagMapper.selectCountByStatus(SafetyFieldTagDO.TAG_STATUS_PENDING));
        vo.setConfirmedTagCount(tagMapper.selectCountByStatus(SafetyFieldTagDO.TAG_STATUS_CONFIRMED));
        // 2. 资产概况
        vo.setIdentifyRuleCount(identifyRuleMapper.selectCount(
                SafetyIdentifyRuleDO::getStatus, SafetyIdentifyRuleDO.STATUS_ENABLE));
        vo.setActiveWhitelistCount(whitelistService.countActiveWhitelist());
        // 3. 近 30 天访问与静态任务
        vo.setAccessCount30d(accessLogMapper.selectCountSince(since));
        vo.setExemptCount30d(accessLogMapper.selectExemptCountSince(since));
        Long runCount = staticTaskLogMapper.selectCount(new LambdaQueryWrapperX<SafetyStaticTaskLogDO>()
                .ge(SafetyStaticTaskLogDO::getStartTime, since));
        vo.setStaticRunCount30d(runCount);
        if (runCount != null && runCount > 0) {
            Long successCount = staticTaskLogMapper.selectCount(new LambdaQueryWrapperX<SafetyStaticTaskLogDO>()
                    .ge(SafetyStaticTaskLogDO::getStartTime, since)
                    .eq(SafetyStaticTaskLogDO::getRunStatus, SafetyStaticTaskLogDO.RUN_STATUS_SUCCESS));
            vo.setStaticRunSuccessRate(NumberUtil.round(successCount * 100.0 / runCount, 2).doubleValue());
        }
        // 4. 分级分布（已确认打标）
        Map<Long, SafetyLevelDO> levelMap = convertMap(levelMapper.selectList(), SafetyLevelDO::getId);
        vo.setLevelDistribution(convertList(tagMapper.selectConfirmedGroupByLevel(), row -> {
            GovernanceSafetySummaryRespVO.LevelItem item = new GovernanceSafetySummaryRespVO.LevelItem();
            Long levelId = toLong(row.get("levelId"));
            item.setLevelId(levelId);
            item.setCount(toLong(row.get("cnt")));
            SafetyLevelDO level = levelId == null ? null : levelMap.get(levelId);
            item.setLevelName(level != null ? level.getSafetyName() : "未分级");
            item.setColor(level != null ? level.getColor() : null);
            return item;
        }));
        // 5. 访问趋势
        vo.setAccessTrend(toTrendItems(accessLogMapper.selectTrendSince(since)));
        return vo;
    }

    /**
     * 转换趋势明细。
     */
    private List<GovernanceSafetySummaryRespVO.TrendItem> toTrendItems(List<Map<String, Object>> rows) {
        return convertList(rows, row -> {
            GovernanceSafetySummaryRespVO.TrendItem item = new GovernanceSafetySummaryRespVO.TrendItem();
            item.setDate(String.valueOf(row.get("day")));
            item.setTotal(toLong(row.get("total")));
            item.setExempt(toLong(row.get("exempt")));
            return item;
        });
    }

    /**
     * 转换长整数。
     */
    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

}
