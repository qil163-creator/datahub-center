package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelOverviewRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelOverviewRespVO.Activity;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelOverviewRespVO.Distribution;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelOverviewRespVO.Metric;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelOverviewMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.BizCategoryMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 建模概览沿用各资源列表的平台可见范围，按资源查询权限裁剪，不能借概览读取无权查询的名称或数量。
 * 领域聚合使用现有 MyBatis 和安全组件；本服务不引入数据库技术机制或历史快照推断。
 */
@Service
@Validated
public class ModelOverviewServiceImpl implements ModelOverviewService {

    @Resource
    private DataDomainMapper dataDomainMapper;

    @Resource
    private SubjectDomainMapper subjectDomainMapper;

    @Resource
    private BizCategoryMapper bizCategoryMapper;

    @Resource
    private LogicModelMapper logicModelMapper;

    @Resource
    private ModelOverviewMapper overviewMapper;

    @Resource
    private SecurityFrameworkService securityFrameworkService;

    /**
     * 获得数据建模总览统计（数据建模首页「数据概览」面板）
     *
     * @return 模型概览
     */
    @Override
    public ModelOverviewRespVO getModelOverview() {
        ModelOverviewRespVO response = new ModelOverviewRespVO();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(7);
        response.setGeneratedAt(now);
        boolean canReadDomains = securityFrameworkService.hasPermission("modeling:data-domain:query");
        boolean canReadSubjects = securityFrameworkService.hasPermission("modeling:subject-domain:query");
        boolean canReadModels = securityFrameworkService.hasPermission("modeling:model:query");
        boolean canReadMaterialized = securityFrameworkService.hasPermission("modeling:model-materialized:query");
        boolean canReadConcepts = securityFrameworkService.hasPermission("modeling:concept-model:query");
        boolean canReadIndicators = securityFrameworkService.hasPermission("modeling:indicator-model:query");

        Metric concepts = metric("conceptModel", "概念模型", canReadConcepts,
                "当前未删除的概念模型；近 7 日新增按创建时间统计");
        if (canReadConcepts) {
            var counts = overviewMapper.selectConceptCounts(since, now);
            concepts.setCount(counts.getTotal());
            concepts.setRecentCount(counts.getRecent());
        }
        Metric indicators = metric("indicatorModel", "指标模型", canReadIndicators,
                "当前未删除的原子指标和派生指标总数；近 7 日新增按创建时间统计");
        if (canReadIndicators) {
            var counts = overviewMapper.selectIndicatorCounts(since, now);
            indicators.setCount(counts.getTotal());
            indicators.setRecentCount(counts.getRecent());
        }

        Metric dataDomain = metric("dataDomain", "数据板块", canReadDomains,
                "按现有数据域统计；近 7 日新增仅统计当前未删除的记录");
        if (canReadDomains) {
            dataDomain.setCount(dataDomainMapper.selectCount());
            dataDomain.setRecentCount(dataDomainMapper.selectCount(new LambdaQueryWrapperX<DataDomainDO>()
                    .between(DataDomainDO::getCreateTime, since, now)));
            response.setDataDomainCount(dataDomain.getCount());
        }
        Metric subjectDomain = metric("subjectDomain", "主题域", canReadSubjects,
                "主题域总数；近 7 日新增仅统计当前未删除的记录");
        if (canReadSubjects) {
            subjectDomain.setCount(subjectDomainMapper.selectCount());
            subjectDomain.setRecentCount(subjectDomainMapper.selectCount(new LambdaQueryWrapperX<SubjectDomainDO>()
                    .between(SubjectDomainDO::getCreateTime, since, now)));
        }
        if (securityFrameworkService.hasPermission("modeling:biz-category:query")) {
            response.setBusinessDomainCount(bizCategoryMapper.selectCount());
        }

        Metric models = metric("logicalModel", "逻辑模型", canReadModels, "当前非废弃逻辑模型数");
        Metric published = metric("publishedModel", "已发布模型", canReadModels,
                "当前已发布模型数；近 7 日发布按真实发布时间统计非废弃模型，重复发布只计一次");
        response.setLayerDistributionAvailable(canReadModels);
        response.setRecentActivitiesAvailable(canReadModels);
        if (canReadModels) {
            models.setCount(logicModelMapper.selectCountByStatusNe(LogicModelDO.STATUS_DISCARDED));
            models.setRecentCount(logicModelMapper.selectCount(new LambdaQueryWrapperX<LogicModelDO>()
                    .ne(LogicModelDO::getStatus, LogicModelDO.STATUS_DISCARDED)
                    .between(LogicModelDO::getCreateTime, since, now)));
            published.setCount(logicModelMapper.selectCountByStatus(LogicModelDO.STATUS_PUBLISHED));
            published.setRecentCount(overviewMapper.selectRecentlyPublishedCount(since, now));
            response.setModelCount(models.getCount());
            response.setPublishedCount(published.getCount());
            response.setLayerDistribution(
                    BeanUtils.toBean(overviewMapper.selectLayerDistribution(), Distribution.class));
            response.setRecentActivities(overviewMapper.selectRecentActivities().stream()
                    .map(this::activity).toList());
        }

        response.setMaterializedDistributionAvailable(canReadMaterialized);
        if (canReadMaterialized) {
            List<Distribution> distribution = BeanUtils.toBean(
                    overviewMapper.selectMaterializedDistribution(), Distribution.class);
            response.setMaterializedDistribution(distribution);
            response.setMaterializedCount(distribution.stream().mapToLong(Distribution::getCount).sum());
            response.setFactCount(countLayers(distribution, List.of("DWD", "DWS", "ADS")));
            response.setDimensionCount(countLayers(distribution, List.of("DIM")));
        }
        response.setMetrics(List.of(dataDomain, subjectDomain,
                concepts, models, indicators, published));
        return response;
    }

    /**
     * 获取模型概览的指标。
     *
     * @param key 标识键
     * @param label 标签
     * @param available 可用
     * @param description 描述
     * @return 指标
     */
    private Metric metric(String key, String label, boolean available, String description) {
        Metric metric = new Metric();
        metric.setKey(key);
        metric.setLabel(label);
        metric.setAvailable(available);
        metric.setDescription(available ? description : "暂无查询权限");
        return metric;
    }

    /**
     * 统计分层。
     *
     * @param distribution 分布
     * @param codes 编码
     * @return 符合条件或受影响的记录数量
     */
    private long countLayers(List<Distribution> distribution, List<String> codes) {
        return distribution.stream().filter(item -> item.getCode() != null && codes.contains(item.getCode()))
                .mapToLong(Distribution::getCount).sum();
    }

    /**
     * 获取模型概览的活动。
     *
     * @param source 来源数据
     * @return 活动
     */
    private Activity activity(ModelOverviewMapper.RecentActivity source) {
        Activity target = BeanUtils.toBean(source, Activity.class);
        target.setTitle(switch (source.getType()) {
            case "PUBLISH" -> "发布「" + source.getModelName() + "」逻辑模型";
            default -> "新增「" + source.getModelName() + "」逻辑模型";
        });
        return target;
    }
}
