package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntitySaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityRelationDO;

import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.BUSINESS_ENTITY_RELATION_INVALID;

/** 实体关系语义；调用方持有所属板块行锁后，在同一事务中验证和保存。 */
final class BusinessEntityRelationPolicy {

    /**
     * 限制业务实体关系策略工具的实例化，统一通过静态能力使用。
     */
    private BusinessEntityRelationPolicy() {
    }

    /**
     * 校验业务实体关系策略输入及状态。
     *
     * @param id 记录编号
     * @param request 请求参数
     * @param boardEntities 板块实体
     * @param boardRelations 板块关系
     * @return 校验业务实体关系策略输入及状态结果集合
     */
    static List<BusinessEntityRelationDO> validate(Long id, BusinessEntitySaveReqVO request,
            List<BusinessEntityDO> boardEntities, List<BusinessEntityRelationDO> boardRelations) {
        var targets = boardEntities.stream().collect(Collectors.toMap(BusinessEntityDO::getId, e -> e));
        boolean object = "OBJECT".equals(request.getEntityType());
        String subtype = request.getEntitySubtype();
        if (!(object && Set.of("NORMAL", "HIERARCHICAL").contains(subtype)
                || "ACTIVITY".equals(request.getEntityType()) && Set.of("EVENT", "PROCESS").contains(subtype))) {
            invalid("实体类型与子类型不匹配");
        }
        var result = new ArrayList<BusinessEntityRelationDO>();
        if (request.getInheritEntityId() != null) {
            add(result, id, request.getInheritEntityId(), "INHERITANCE", 0);
        }
        addAll(result, id, request.getAssociatedEntityIds(), "ASSOCIATION");
        addAll(result, id, request.getPredecessorEntityIds(), "PREDECESSOR");
        addAll(result, id, request.getProcessEventIds(), "PROCESS_EVENT");
        if ("HIERARCHICAL".equals(subtype) && !result.isEmpty()) {
            invalid("层级对象不配置实体关系");
        }
        if (!object && request.getInheritEntityId() != null) {
            invalid("仅普通对象可以配置继承实体");
        }
        if (!"PROCESS".equals(subtype)
                && result.stream()
                .anyMatch(r -> Set.of("PREDECESSOR", "PROCESS_EVENT").contains(r.getRelationType()))) {
            invalid("仅业务流程可以配置前序流程及流程事件");
        }
        if ("PROCESS".equals(subtype)
                && result.stream().filter(r -> "PROCESS_EVENT".equals(r.getRelationType())).count() < 2) {
            invalid("业务流程至少包含两个不重复的业务事件");
        }
        for (var relation : result) {
            var target = targets.get(relation.getTargetEntityId());
            if (target == null || !Objects.equals(target.getDataDomainId(), request.getDataDomainId())) {
                invalid("关联实体必须存在且属于当前数据板块");
            }
            if (Objects.equals(id, relation.getTargetEntityId())) {
                invalid("实体不能引用自身");
            }
            switch (relation.getRelationType()) {
                case "INHERITANCE" -> {
                    if (!Objects.equals(target.getSubjectDomainId(), request.getSubjectDomainId())) {
                        invalid("继承实体必须属于同一主题域");
                    }
                }
                case "ASSOCIATION" -> {
                    if (Objects.equals(target.getId(), request.getInheritEntityId())) {
                        invalid("继承实体不能同时作为关联实体");
                    }
                }
                case "PREDECESSOR", "PROCESS_EVENT" -> {
                    String required = "PREDECESSOR".equals(relation.getRelationType()) ? "PROCESS" : "EVENT";
                    if (!"ACTIVITY".equals(target.getEntityType()) || !required.equals(target.getEntitySubtype())) {
                        invalid("PREDECESSOR".equals(
                                relation.getRelationType()) ? "前序实体必须为业务流程" : "流程事件必须为业务事件");
                    }
                    if (boardRelations.stream().anyMatch(r -> relation.getRelationType().equals(r.getRelationType())
                            && Objects.equals(target.getId(), r.getTargetEntityId())
                            && !Objects.equals(id, r.getSourceEntityId()))) {
                        invalid("PREDECESSOR".equals(
                                relation.getRelationType()) ? "前序流程已被其他流程占用" : "业务事件已被其他流程包含");
                    }
                }
                default -> invalid("不支持的实体关系");
            }
        }
        var merged = boardRelations.stream().filter(r -> !Objects.equals(id, r.getSourceEntityId()))
                .collect(Collectors.toCollection(ArrayList::new));
        merged.addAll(result);
        if (id != null) {
            for (var incoming : boardRelations) {
                if ("INHERITANCE".equals(incoming.getRelationType()) && Objects.equals(incoming.getTargetEntityId(),
                        id)) {
                    var child = targets.get(incoming.getSourceEntityId());
                    if (child != null && !Objects.equals(child.getSubjectDomainId(), request.getSubjectDomainId())) {
                        invalid("存在继承子实体，不能更换为其他主题域");
                    }
                }
            }
            for (String type : List.of("INHERITANCE", "PREDECESSOR")) {
                if (cycle(id, id, type, merged, new HashSet<>())) {
                    invalid("继承或前序流程不能形成循环");
                }
            }
        }
        return result;
    }

    /**
     * 判断周期条件是否成立。
     *
     * @param origin 原始
     * @param current 当前
     * @param type 类型标识
     * @param rows 待处理的行数据
     * @param visiting 正在访问
     * @return 处理是否成功
     */
    private static boolean cycle(Long origin, Long current, String type, List<BusinessEntityRelationDO> rows,
            Set<Long> visiting) {
        if (!visiting.add(current)) {
            return false;
        }
        for (var row : rows) {
            if (!type.equals(row.getRelationType()) || !Objects.equals(current, row.getSourceEntityId())) {
                continue;
            }
            if (Objects.equals(origin, row.getTargetEntityId())
                    || cycle(origin, row.getTargetEntityId(), type, rows, visiting)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 添加全部。
     *
     * @param result 处理结果
     * @param id 记录编号
     * @param ids 记录编号集合
     * @param type 类型标识
     */
    private static void addAll(List<BusinessEntityRelationDO> result, Long id, List<Long> ids, String type) {
        if (ids == null) {
            return;
        }
        if (ids.size() > 100 || ids.stream().anyMatch(value -> value == null || value <= 0)
                || new HashSet<>(ids).size() != ids.size()) {
            invalid("关系编号必须有效、不重复且最多100个");
        }
        for (int i = 0; i < ids.size(); i++) {
            add(result, id, ids.get(i), type, i);
        }
    }

    /**
     * 添加业务实体关系策略。
     *
     * @param result 处理结果
     * @param source 来源数据
     * @param target 目标数据
     * @param type 类型标识
     * @param order 顺序
     */
    private static void add(List<BusinessEntityRelationDO> result, Long source, Long target, String type, int order) {
        var row = new BusinessEntityRelationDO();
        row.setSourceEntityId(source);
        row.setTargetEntityId(target);
        row.setRelationType(type);
        row.setSortOrder(order);
        result.add(row);
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @param reason 操作原因
     */
    private static void invalid(String reason) {
        throw exception(BUSINESS_ENTITY_RELATION_INVALID, reason);
    }
}
