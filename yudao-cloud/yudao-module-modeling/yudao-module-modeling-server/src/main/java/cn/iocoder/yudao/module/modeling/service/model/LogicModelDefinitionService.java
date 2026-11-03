package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelTypeConfigVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.LogicModelCreateReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.LogicModelRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.SourceSystemMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_TYPE_CONFIG_INVALID_VALUE;

/** 逻辑表领域定义：平台内全可见，复用已有规划实体、指标和统一模型存储。 */
@Service
public class LogicModelDefinitionService {

    @Resource
    private BusinessEntityMapper entities;

    @Resource
    private IndicatorModelMapper indicators;

    @Resource
    private cn.iocoder.yudao.module.modeling.service.indicator.IndicatorModelService indicatorService;

    @Resource
    private LogicModelMapper models;

    @Resource
    private SourceSystemMapper sources;

    @Resource
    private AdminUserApi users;

    @Resource
    private LogicModelColumnService columns;

    /**
     * 规范化逻辑模型定义。
     *
     * @param request 请求参数
     */
    public void normalize(LogicModelCreateReqVO request) {
        if (request.getSourceSystemId() != null) {
            var source = sources.selectById(request.getSourceSystemId());
            if (source == null || !Integer.valueOf(0).equals(source.getStatus())) {
                invalid("来源系统不存在或已停用");
            }
        }
        if (request.getEntityId() != null) {
            BusinessEntityDO entity = entity(request.getEntityId(), request.getDataDomainId(),
                    request.getSubjectDomainId());
            if ("DIMENSION".equals(request.getTableType()) && !"OBJECT".equals(entity.getEntityType())
                    || "DETAIL".equals(request.getTableType()) && !"ACTIVITY".equals(entity.getEntityType())) {
                invalid("业务实体类型与逻辑表类型不一致");
            }
            request.setObjType("HIERARCHICAL".equals(entity.getEntitySubtype()) ? "2" : "1");
            request.setBusinessType("PROCESS".equals(entity.getEntitySubtype()) ? "2" : "1");
            if (Set.of("DIMENSION", "DETAIL").contains(Objects.toString(request.getTableType(), ""))
                    && StrUtil.isNotBlank(request.getDataTimeliness())) {
                long count = models.selectCount(new LambdaQueryWrapperX<LogicModelDO>()
                        .eq(LogicModelDO::getTableType, request.getTableType())
                        .eq(LogicModelDO::getEntityId, request.getEntityId())
                        .eq(LogicModelDO::getDataTimeliness, request.getDataTimeliness())
                        .ne(LogicModelDO::getStatus, LogicModelDO.STATUS_DISCARDED)
                        .ne(request.getId() != null, LogicModelDO::getId, request.getId()));
                if (count > 0) {
                    invalid("当前业务实体与统计时效已创建逻辑表");
                }
            }
        }
        if (StrUtil.isNotBlank(request.getDataTimeliness())
                && !Set.of("1", "2", "3").contains(request.getDataTimeliness())) {
            invalid("统计时效不合法");
        }
        if (request.getDatasourceId() == null) {
            request.setDatasourceId(request.getDatabaseId());
        }
        if ("SOURCE".equals(request.getTableType()) && request.getSourceSystemId() != null) {
            ModelTypeConfigVO config = request.getTypeConfig() == null ? new ModelTypeConfigVO() : request.getTypeConfig();
            ModelTypeConfigVO.SourceConfig source = config.getSource() == null ? new ModelTypeConfigVO.SourceConfig() : config.getSource();
            source.setSourceSystem(sources.selectById(request.getSourceSystemId()).getName());
            if (StrUtil.isNotBlank(request.getTableName())) {
                source.setSourceTable(request.getTableName());
            }
            if (StrUtil.isBlank(source.getIngestMode())) {
                source.setIngestMode("CURRENT_STATE");
            }
            config.setSource(source);
            request.setTypeConfig(config);
        }
        String prefix = switch (Objects.toString(request.getTableType(), "")) {
            case "SOURCE" -> "ODS_";
            case "DIMENSION" -> "DIM_";
            case "DETAIL" -> "DWD_";
            case "SUMMARY" -> "DWS_";
            case "APPLICATION" -> "ADS_";
            default -> "";
        };
        if (StrUtil.isBlank(request.getTableNameNotation()) && StrUtil.isNotBlank(request.getCode())) {
            request.setTableNameNotation(prefix + request.getCode());
        }
        if (request.getTableNameNotation() != null) {
            request.setTableNameNotation(Integer.valueOf(2).equals(request.getTableCase())
                    ? request.getTableNameNotation().toLowerCase(Locale.ROOT)
                    : request.getTableNameNotation().toUpperCase(Locale.ROOT));
        }
        if ("SUMMARY".equals(request.getTableType()) && (request.getGrainEntityIds() != null
                || StrUtil.isNotBlank(request.getDataTimeliness()))) {
            List<Long> ids = request.getGrainEntityIds() == null ? List.of() : request.getGrainEntityIds().stream()
                    .distinct().toList();
            request.setGrainEntityIds(ids);
            request.setStatisticsStr(String.join(",", ids.stream().map(id ->
                    entity(id, request.getDataDomainId(), request.getSubjectDomainId()).getName()).toList()));
            request.setFields(summaryFields(request.getDataDomainId(), request.getSubjectDomainId(),
                    request.getDataTimeliness(), ids));
        }
    }

    /** 预览与实际保存调用相同生成路径，客户端不能伪造时间、粒度或指标字段。 */
    public List<ModelFieldItem> summaryFields(Long domainId, Long subjectId, String timeliness, List<Long> ids) {
        List<Long> grainIds = ids == null ? List.of() : ids.stream().distinct().toList();
        if (grainIds.size() > 32) {
            invalid("统计粒度最多选择 32 项");
        }
        List<ModelFieldItem> result = new ArrayList<>();
        if (StrUtil.isNotBlank(timeliness)) {
            if (!Set.of("1", "2", "3").contains(timeliness)) {
                invalid("统计时效不合法");
            }
            ModelFieldItem time = field("summary:time", "ds", switch (timeliness) {
                case "2" -> "统计小时";
                case "3" -> "统计分钟";
                default -> "统计日期";
            }, "DATE", "TIME");
            time.setPkFlag(true);
            time.setNullableFlag(false);
            time.setComment("汇总数据所属业务日期");
            result.add(time);
        }
        for (Long id : grainIds) {
            BusinessEntityDO entity = entity(id, domainId, subjectId);
            if (!"OBJECT".equals(entity.getEntityType()) || !"NORMAL".equals(entity.getEntitySubtype())) {
                invalid("统计粒度只能选择已上线的普通业务对象");
            }
            String code = StrUtil.blankToDefault(entity.getCode(), "entity_" + id).toLowerCase(Locale.ROOT);
            ModelFieldItem grain = field("summary:grain:" + id, code + "_id", entity.getName() + "编码", "VARCHAR",
                    "GRAIN");
            grain.setDataLength(255);
            grain.setDimensionId(id);
            grain.setDimensionName(entity.getName());
            grain.setComment(entity.getDescription());
            grain.setPkFlag(true);
            grain.setNullableFlag(false);
            result.add(grain);
        }
        if (domainId != null && subjectId != null && timeliness != null && !grainIds.isEmpty()) {
            List<IndicatorModelDO> candidates = indicators.selectList(new LambdaQueryWrapperX<IndicatorModelDO>()
                    .eq(IndicatorModelDO::getDataDomainId, domainId).eq(IndicatorModelDO::getSubjectDomainId, subjectId)
                    .eq(IndicatorModelDO::getIndicatorType, IndicatorModelDO.DERIVED)
                    .eq(IndicatorModelDO::getStatus, IndicatorModelDO.ACTIVE).orderByAsc(IndicatorModelDO::getId));
            Set<Long> modelIds = new HashSet<>(), atomicIds = new HashSet<>();
            for (IndicatorModelDO candidate : candidates) {
                if (candidate.getBaseIndicatorId() != null) {
                    atomicIds.add(candidate.getBaseIndicatorId());
                }
            }
            Map<Long, IndicatorModelDO> byAtomic = new HashMap<>();
            if (!atomicIds.isEmpty()) {
                for (IndicatorModelDO atomic : indicators.selectByIds(atomicIds)) {
                    byAtomic.put(atomic.getId(), atomic);
                }
            }
            for (IndicatorModelDO atomic : byAtomic.values()) {
                if (atomic.getSourceModelId() != null) {
                    modelIds.add(atomic.getSourceModelId());
                }
            }
            Map<Long, LogicModelDO> byModel = new HashMap<>();
            if (!modelIds.isEmpty()) {
                for (LogicModelDO model : models.selectByIds(modelIds)) {
                    byModel.put(model.getId(), model);
                }
            }
            Map<Long, List<ModelFieldItem>> byFields = columns.listByModelIds(modelIds);
            Map<Long, Boolean> usableAtomicSources = new HashMap<>();
            for (IndicatorModelDO indicator : candidates) {
                // 派生指标按数据库约束没有独立来源，只能沿真实原子指标绑定的冻结任务输入解析。
                IndicatorModelDO atomic = byAtomic.get(indicator.getBaseIndicatorId());
                if (atomic == null || !IndicatorModelDO.ATOMIC.equals(atomic.getIndicatorType())
                        || !IndicatorModelDO.ACTIVE.equals(atomic.getStatus())
                        || !Objects.equals(atomic.getDataDomainId(), domainId)
                        || !matchesTimeliness(timeliness, atomic.getDataFreshness())
                        || StrUtil.isNotBlank(indicator.getDataFreshness()) && !matchesTimeliness(timeliness,
                        indicator.getDataFreshness())) {
                    continue;
                }
                LogicModelDO model = byModel.get(atomic.getSourceModelId());
                if (!matchesGrain(grainIds, atomic, model, byFields.getOrDefault(atomic.getSourceModelId(), List.of()))
                        || !usableAtomicSources.computeIfAbsent(atomic.getId(), ignored -> indicatorService.isAtomicSourceUsable(atomic))) {
                    continue;
                }
                ModelFieldItem field = field("summary:indicator:" + indicator.getId(), indicator.getCode(),
                        indicator.getName(),
                        StrUtil.blankToDefault(indicator.getResultDataType(), "DECIMAL"), "INDICATOR");
                field.setComment(indicator.getDefinition());
                field.setRemark(indicator.getDescription());
                field.setMeasureUnit(indicator.getUnit());
                field.setIndicatorId(indicator.getId());
                field.setBusinessCondition(indicator.getBusinessCondition());
                field.setStatisticsPeriod(indicator.getTimePeriod());
                field.setAtomicIndicatorName(atomic.getName());
                field.setSourceModelName(model.getName());
                result.add(field);
            }
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < result.size(); i++) {
            ModelFieldItem field = result.get(i);
            field.setSort(i);
            if (!names.add(field.getFieldName().toLowerCase(Locale.ROOT))) {
                invalid("自动字段名称重复，请调整业务实体或指标编码");
            }
        }
        return result;
    }

    /**
     * 组装逻辑模型定义展示数据。
     *
     * @param model 模型
     * @param response 响应对象
     */
    public void present(LogicModelDO model, LogicModelRespVO response) {
        presentAll(List.of(model), List.of(response));
    }

    /** 批量富化避免来源表选择器对每张表重复读取用户。 */
    public void presentAll(List<LogicModelDO> rows, List<LogicModelRespVO> responses) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, LogicModelDO> byId = new HashMap<>();
        Set<Long> sourceIds = new HashSet<>(), entityIds = new HashSet<>(), userIds = new HashSet<>();
        for (LogicModelDO row : rows) {
            byId.put(row.getId(), row);
            if (row.getSourceSystemId() != null) {
                sourceIds.add(row.getSourceSystemId());
            }
            if (row.getEntityId() != null) {
                entityIds.add(row.getEntityId());
            }
            if (row.getContactUserId() != null) {
                userIds.add(row.getContactUserId());
            }
            if (StrUtil.isNumeric(row.getCreator())) {
                userIds.add(Long.valueOf(row.getCreator()));
            }
        }
        var sourceRows = sourceIds.isEmpty() ? List.<cn.iocoder.yudao.module.modeling.dal.dataobject.model.SourceSystemDO>of() : sources.selectByIds(
                sourceIds);
        var entityRows = entityIds.isEmpty() ? List.<BusinessEntityDO>of() : entities.selectByIds(entityIds);
        var byUser = userIds.isEmpty() ? Map.<Long, cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO>of() : users.getUserMap(
                userIds);
        for (LogicModelRespVO response : responses) {
            LogicModelDO model = byId.get(response.getId());
            response.setGrainEntityIds(model.getGrainEntityIds() == null ? List.of() : model.getGrainEntityIds());
            sourceRows.stream().filter(source -> Objects.equals(source.getId(), model.getSourceSystemId()))
                    .findFirst().ifPresent(source -> response.setSourceSystemName(source.getName()));
            entityRows.stream().filter(entity -> Objects.equals(entity.getId(), model.getEntityId()))
                    .findFirst().ifPresent(entity -> response.setEntityName(entity.getName()));
            if (StrUtil.isNotBlank(model.getTypeConfig())) {
                ModelTypeConfigVO config = JsonUtils.parseObject(model.getTypeConfig(), ModelTypeConfigVO.class);
                if (config != null && config.getSource() != null) {
                    response.setSourceTableName(config.getSource().getSourceTable());
                    response.setTableName(config.getSource().getSourceTable());
                }
            }
            var owner = model.getContactUserId() == null ? null : byUser.get(model.getContactUserId());
            if (owner != null) {
                response.setOwnerName(owner.getNickname());
                response.setOwnerPhone(owner.getMobile());
            }
            var creator = StrUtil.isNumeric(model.getCreator()) ? byUser.get(Long.valueOf(model.getCreator())) : null;
            if (creator != null) {
                response.setCreatorName(creator.getNickname());
            }
        }
    }

    /**
     * 获取逻辑模型定义的实体。
     *
     * @param id 记录编号
     * @param domain 域
     * @param subject 主体
     * @return 业务实体
     */
    private BusinessEntityDO entity(Long id, Long domain, Long subject) {
        BusinessEntityDO entity = id == null ? null : entities.selectById(id);
        if (entity == null || !Objects.equals(domain, entity.getDataDomainId())
                || !Objects.equals(subject, entity.getSubjectDomainId())
                || !Integer.valueOf(0).equals(entity.getStatus())) {
            invalid("业务实体未上线或不属于当前板块和主题域");
        }
        return entity;
    }

    /** 仅当前已发布启用来源的稳定字段与粒度一致时，才生成派生指标字段。 */
    private boolean matchesGrain(List<Long> grainIds, IndicatorModelDO indicator, LogicModelDO model,
            List<ModelFieldItem> fields) {
        if (model == null || !LogicModelDO.STATUS_PUBLISHED.equals(model.getStatus())
                || !Integer.valueOf(1).equals(model.getEnabled())
                || !Objects.equals(model.getEntityId(), indicator.getEntityId())
                || !Objects.equals(model.getDataDomainId(), indicator.getDataDomainId())
                || !Objects.equals(model.getSubjectDomainId(), indicator.getSubjectDomainId())
                || !Objects.equals(model.getDataTimeliness(), indicator.getDataFreshness())
                || fields.stream().noneMatch(field -> Objects.equals(field.getFieldUid(), indicator.getValueFieldUid()))
                || StrUtil.isNotBlank(indicator.getTimeFieldUid()) && fields.stream().noneMatch(
                        field -> Objects.equals(field.getFieldUid(), indicator.getTimeFieldUid()))) {
            return false;
        }
        Set<Long> dimensions = new HashSet<>();
        for (ModelFieldItem field : fields) {
            if (field.getDimensionId() != null) {
                dimensions.add(field.getDimensionId());
            }
        }
        return dimensions.equals(new HashSet<>(grainIds));
    }

    /**
     * 获取逻辑模型定义的字段。
     *
     * @param uid 用户标识
     * @param name 名称
     * @param cn 中文
     * @param type 类型标识
     * @param category 分类
     * @return 模型字段项
     */
    private static ModelFieldItem field(String uid, String name, String cn, String type, String category) {
        ModelFieldItem field = new ModelFieldItem();
        field.setFieldUid(UUID.nameUUIDFromBytes(uid.getBytes(StandardCharsets.UTF_8)).toString());
        field.setFieldName(name);
        field.setCnName(cn);
        field.setDataType(type);
        field.setFieldCategory(category);
        field.setPkFlag(false);
        field.setNullableFlag(true);
        return field;
    }

    /**
     * 判断是否匹配时效性条件。
     *
     * @param selected 已选择
     * @param freshness 新鲜度
     * @return 判断结果
     */
    private static boolean matchesTimeliness(String selected, String freshness) {
        if (freshness == null) {
            return false;
        }
        String value = freshness.trim().toUpperCase(Locale.ROOT);
        return switch (selected) {
            case "1" -> Set.of("1", "DAY", "T+1", "天（T+1）", "天 (T+1)").contains(value);
            case "2" -> Set.of("2", "HOUR", "T+H", "小时（T+H）", "小时 (T+H)").contains(value);
            case "3" -> Set.of("3", "MINUTE", "T+M", "分钟（T+H）", "分钟 (T+H)").contains(value);
            default -> false;
        };
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @param message 消息
     */
    private static void invalid(String message) {
        throw exception(MODEL_TYPE_CONFIG_INVALID_VALUE, message);
    }
}
