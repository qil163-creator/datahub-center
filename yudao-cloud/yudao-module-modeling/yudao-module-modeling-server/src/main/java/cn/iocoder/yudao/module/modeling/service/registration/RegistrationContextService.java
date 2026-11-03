package cn.iocoder.yudao.module.modeling.service.registration;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.api.registration.dto.*;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.DataDomainNamingRuleVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerListReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.SubjectDomainListReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.service.planning.*;
import cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityService;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.system.api.dict.DictDataApi;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/** 注册命名只编排现有板块、实体、主题和分层服务，规则仍归属建模领域。 */
@Service
public class RegistrationContextService {

    @Resource
    private DataDomainService domains;

    @Resource
    private SubjectDomainService subjects;

    @Resource
    private DwLayerService layers;

    @Resource
    private BusinessEntityService entities;

    @Resource
    private ModelHierarchyResolver hierarchy;

    @Resource
    private DictDataApi dictionaries;

    /**
     * 读取全平台已启用的业务选项白名单。
     *
     * @param boardId 板块编号
     * @param type 类型标识
     * @param subjectId 主体编号
     * @return 登记上下文响应数据
     */
    public RegistrationContextRespDTO context(Long boardId, String type, Long subjectId) {
        if (type != null && !Set.of("ODS", "DIM", "DWD", "DWS", "ADS").contains(type)) {
            throw invalid("表类型无效");
        }
        var result = new RegistrationContextRespDTO();
        result.setDataDomains(
                domains.getDataDomainSimpleList().stream().filter(row -> Objects.equals(row.getStatus(), 0))
                        .map(row -> new RegistrationContextRespDTO.Domain(row.getId(), row.getName(), row.getCode()))
                        .toList());
        var layerQuery = new DwLayerListReqVO();
        layerQuery.setStatus((short) 0);
        result.setLayers(layers.getDwLayerList(layerQuery).stream()
                .filter(row -> "LAYER".equals(row.getNodeType()) && Objects.equals(row.getStatus(), (short) 0))
                .map(row -> new RegistrationContextRespDTO.Layer(row.getId(), row.getLayerName(), row.getLayerCode(),
                        row.getTablePrefix(), row.getDefaultTableType())).toList());
        result.setTimeEffects(dictionaries.getDictDataList("modeling_logic_data_timeliness").getCheckedData().stream()
                .filter(row -> Objects.equals(row.getStatus(), 0) && Set.of("1", "2", "3").contains(row.getValue()))
                .map(row -> new RegistrationContextRespDTO.TimeEffect(row.getValue(), row.getLabel())).toList());
        result.setEntities(List.of());
        result.setGranularities(List.of());
        result.setSubjectDomains(List.of());
        result.setNamingRules(List.of());
        if (boardId == null) {
            if (subjectId != null) {
                throw invalid("请先选择真实数据板块");
            }
            result.setNamingRules(
                    rules(null).stream().filter(rule -> Set.of("ODS", "ADS").contains(rule.tableType())).toList());
            return result;
        }
        DataDomainDO board = hierarchy.validateModelDataDomain(boardId);
        if (board == null || !Objects.equals(board.getStatus(), 0)) {
            throw invalid("数据板块未启用");
        }
        hierarchy.validateSubjectDomain(subjectId, null, boardId);
        result.setSelectedDataDomainId(boardId);
        result.setSelectionSource("EXPLICIT");
        var query = new SubjectDomainListReqVO();
        query.setDataDomainId(boardId);
        query.setStatus(0);
        result.setSubjectDomains(subjects.getSubjectDomainList(query).stream()
                .filter(row -> Objects.equals(row.getDataDomainId(), boardId) && Objects.equals(row.getStatus(), 0))
                .map(row -> new RegistrationContextRespDTO.Subject(row.getId(), row.getParentId(),
                        row.getDataDomainId(), row.getName(), row.getCode(), row.getAbbreviation())).toList());
        Set<Long> enabledSubjects = result.getSubjectDomains().stream().map(RegistrationContextRespDTO.Subject::id)
                .collect(Collectors.toSet());
        var entityList = entities.options(boardId, null).getEntities().stream()
                .filter(row -> Objects.equals(row.getStatus(), 0))
                .filter(row -> enabledSubjects.contains(row.getSubjectDomainId()))
                .filter(row -> subjectId == null || Objects.equals(row.getSubjectDomainId(), subjectId))
                .map(row -> new RegistrationContextRespDTO.Entity(row.getId(), row.getName(), row.getCode(),
                        row.getSubjectDomainId(), row.getEntityType(), row.getEntitySubtype())).toList();
        result.setGranularities(entityList.stream()
                .filter(row -> "OBJECT".equals(row.entityType()) && "NORMAL".equals(row.entitySubtype())).toList());
        result.setEntities(entityList.stream()
                .filter(row -> "DIM".equals(type) ? "OBJECT".equals(row.entityType()) : !"DWD".equals(
                        type) || "ACTIVITY".equals(row.entityType())).toList());
        result.setNamingRules(rules(board));
        return result;
    }

    /**
     * 读取登记上下文规则集合。
     *
     * @param board 板块
     * @return 登记上下文规则集合结果集合
     */
    private List<RegistrationContextRespDTO.NamingRule> rules(DataDomainDO board) {
        var rules = new LinkedHashMap<String, DataDomainNamingRuleVO>();
        DataDomainNamingRuleVO.detailDefaults().forEach(rule -> rules.put(rule.getTableType(), rule));
        if (board != null && StrUtil.isNotBlank(board.getNamingRules())) {
            JsonUtils.parseArray(board.getNamingRules(), DataDomainNamingRuleVO.class)
                    .forEach(rule -> rules.put(rule.getTableType(), rule));
        }
        return rules.values().stream()
                .map(rule -> new RegistrationContextRespDTO.NamingRule(rule.getTableType(), rule.getPrefix(),
                        rule.getDaySuffix(), rule.getHourSuffix(), rule.getMinuteSuffix(),
                        rule.getIncludeSubjectAbbreviation())).toList();
    }

    /**
     * 按真实业务选择和持久命名规则生成推荐名称。
     *
     * @param request 请求参数
     * @return 登记名称响应数据
     */
    public RegistrationNameRespDTO preview(RegistrationNameReqDTO request) {
        if (request.getDataDomainId() == null && !Set.of("ODS", "ADS").contains(request.getTableType())) {
            throw invalid("当前表类型必须选择真实数据板块");
        }
        var context = context(request.getDataDomainId(), request.getTableType(), request.getSubjectDomainId());
        String type = request.getTableType();
        var subject = context.getSubjectDomains().stream()
                .filter(row -> Objects.equals(row.id(), request.getSubjectDomainId())).findFirst().orElse(null);
        var entity = context.getEntities().stream().filter(row -> Objects.equals(row.id(), request.getEntityId()))
                .findFirst().orElse(null);
        if (Set.of("DIM", "DWD").contains(type) && entity == null) {
            throw invalid("请选择当前板块和主题下已启用的业务实体");
        }
        String ruleType = "DWD".equals(type) ? ("PROCESS".equals(
                entity.entitySubtype()) ? "FACT_PROCESS" : "FACT_EVENT") : type;
        var rule = context.getNamingRules().stream().filter(row -> row.tableType().equals(ruleType)).findFirst()
                .orElseThrow(() -> invalid("缺少正式命名规则"));
        String prefix = rule.prefix().replaceAll("_+$", "");
        String suffix = request.getTimeEffect() == null ? "" : Objects.toString(switch (request.getTimeEffect()) {
            case "1" -> rule.daySuffix();
            case "2" -> rule.hourSuffix();
            case "3" -> rule.minuteSuffix();
            default -> throw invalid("数据时效无效");
        }, "");
        String time = request.getTimeEffect() == null ? "" : context.getTimeEffects().stream()
                .filter(row -> row.value().equals(request.getTimeEffect()))
                .map(RegistrationContextRespDTO.TimeEffect::label)
                .findFirst().orElseThrow(() -> invalid("数据时效不存在或未启用"));
        List<String> names = new ArrayList<>();
        names.add(switch (type) {
            case "ODS" -> "贴源逻辑表";
            case "DIM" -> "维度逻辑表";
            case "DWD" -> "事实逻辑表";
            case "DWS" -> "汇总逻辑表";
            case "ADS" -> "应用逻辑表";
            default -> throw invalid("表类型无效");
        });
        String english;
        if (Set.of("ODS", "ADS").contains(type)) {
            String source = request.getSourceTableName().trim();
            english = source.toLowerCase(Locale.ROOT)
                    .startsWith(prefix.toLowerCase(Locale.ROOT) + "_") ? source : prefix + "_" + source;
            names.add(StrUtil.blankToDefault(request.getSourceTableComment(), source));
        } else {
            List<String> parts = new ArrayList<>();
            parts.add(prefix);
            boolean includeSubject = "DWS".equals(type) || Boolean.TRUE.equals(rule.includeSubjectAbbreviation());
            if (includeSubject) {
                if (subject == null) {
                    throw invalid("当前命名规则要求选择真实主题域");
                }
                parts.add(StrUtil.blankToDefault(subject.abbreviation(), subject.code()));
                names.add(subject.name());
            }
            if ("DWS".equals(type)) {
                var ids = request.getGranularityEntityIds() == null ? List.<Long>of() : request.getGranularityEntityIds();
                if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) {
                    throw invalid("请选择不重复的真实统计粒度");
                }
                var byId = context.getGranularities().stream()
                        .collect(Collectors.toMap(RegistrationContextRespDTO.Entity::id, row -> row));
                List<String> codes = new ArrayList<>();
                for (Long id : ids) {
                    var grain = byId.get(id);
                    if (grain == null) {
                        throw invalid("统计粒度不属于当前板块和主题");
                    }
                    codes.add(grain.code());
                    names.add(grain.name());
                }
                parts.add(String.join("", codes));
            } else {
                parts.add(entity.code());
                names.add(entity.name());
            }
            english = String.join("_", parts) + suffix;
            if (!time.isEmpty()) {
                names.add(time);
            }
        }
        english = switch (request.getNamingCase()) {
            case "UPPER" -> english.toUpperCase(Locale.ROOT);
            case "LOWER" -> english.toLowerCase(Locale.ROOT);
            case "MIXED" -> english;
            default -> throw invalid("命名大小写无效");
        };
        String chinese = String.join("_", names);
        if (english.length() > 256 || chinese.length() > 200) {
            throw invalid("生成名称超出资产字段长度，请调整实体或粒度选择");
        }
        var result = new RegistrationNameRespDTO();
        result.setDataDomainId(request.getDataDomainId());
        result.setRuleType(ruleType);
        result.setAssetName(chinese);
        result.setBusinessEnglishName(english);
        return result;
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @param reason 操作原因
     * @return 运行时异常
     */
    private static RuntimeException invalid(String reason) {
        return exception(BUSINESS_ENTITY_RELATION_INVALID, reason);
    }
}
