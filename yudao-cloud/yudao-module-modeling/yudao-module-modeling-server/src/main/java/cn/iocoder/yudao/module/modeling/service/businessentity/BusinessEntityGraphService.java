package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntityGraphRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityRelationDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityRelationMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.BUSINESS_ENTITY_NOT_EXISTS;

/**
 * 业务实体关系图：以实体为中心、一跳范围内的继承、关联、前置、流程事件关系，以及用该实体创建的逻辑表。只读。
 */
@Service
public class BusinessEntityGraphService {

    /** 单张关系图最多展示的逻辑表数量。 */
    static final int MAX_MODELS = 50;

    private static final Map<String, String> LABELS = Map.of("INHERITANCE", "继承", "ASSOCIATION", "关联",
            "PREDECESSOR", "前置流程", "PROCESS_EVENT", "流程事件", "LOGIC_MODEL", "逻辑表");

    @Resource
    private BusinessEntityMapper entityMapper;

    @Resource
    private BusinessEntityRelationMapper relationMapper;

    @Resource
    private LogicModelMapper modelMapper;

    /**
     * 构建关系图。
     *
     * @param entityId 中心实体编号
     * @return 节点与边
     */
    public BusinessEntityGraphRespVO graph(Long entityId) {
        BusinessEntityDO center = entityMapper.selectById(entityId);
        if (center == null) {
            throw exception(BUSINESS_ENTITY_NOT_EXISTS);
        }
        Map<Long, BusinessEntityDO> board = entityMapper.selectByBoard(center.getDataDomainId()).stream()
                .collect(Collectors.toMap(BusinessEntityDO::getId, Function.identity(), (left, right) -> left));
        board.putIfAbsent(center.getId(), center);
        BusinessEntityGraphRespVO graph = new BusinessEntityGraphRespVO();
        graph.setCenterKey(entityKey(center.getId()));
        Map<String, BusinessEntityGraphRespVO.Node> nodes = new LinkedHashMap<>();
        nodes.put(graph.getCenterKey(), entityNode(center));
        for (BusinessEntityRelationDO relation : relationMapper.selectByBoard(center.getDataDomainId())) {
            boolean outgoing = Objects.equals(relation.getSourceEntityId(), entityId);
            boolean incoming = Objects.equals(relation.getTargetEntityId(), entityId);
            if (!outgoing && !incoming) {
                continue;
            }
            BusinessEntityDO source = board.get(relation.getSourceEntityId());
            BusinessEntityDO target = board.get(relation.getTargetEntityId());
            if (source == null || target == null) {
                continue;
            }
            nodes.putIfAbsent(entityKey(source.getId()), entityNode(source));
            nodes.putIfAbsent(entityKey(target.getId()), entityNode(target));
            graph.getEdges().add(edge(entityKey(source.getId()), entityKey(target.getId()), relation.getRelationType()));
        }
        List<LogicModelDO> models = modelMapper.selectList(new LambdaQueryWrapperX<LogicModelDO>()
                .eq(LogicModelDO::getEntityId, entityId)
                .orderByAsc(LogicModelDO::getId)
                .last("LIMIT " + (MAX_MODELS + 1)));
        if (models.size() > MAX_MODELS) {
            graph.setModelsTruncated(true);
            models = models.subList(0, MAX_MODELS);
        }
        for (LogicModelDO model : models) {
            BusinessEntityGraphRespVO.Node node = new BusinessEntityGraphRespVO.Node();
            node.setKey("MODEL:" + model.getId());
            node.setKind("MODEL");
            node.setRefId(model.getId());
            node.setName(model.getName());
            node.setCode(model.getTableNameNotation() == null ? model.getCode() : model.getTableNameNotation());
            node.setSubtype(model.getTableType());
            node.setStatus(model.getStatus());
            nodes.put(node.getKey(), node);
            graph.getEdges().add(edge(graph.getCenterKey(), node.getKey(), "LOGIC_MODEL"));
        }
        graph.setNodes(new ArrayList<>(nodes.values()));
        return graph;
    }

    private static String entityKey(Long id) {
        return "ENTITY:" + id;
    }

    private static BusinessEntityGraphRespVO.Node entityNode(BusinessEntityDO entity) {
        BusinessEntityGraphRespVO.Node node = new BusinessEntityGraphRespVO.Node();
        node.setKey(entityKey(entity.getId()));
        node.setKind("ENTITY");
        node.setRefId(entity.getId());
        node.setName(entity.getName());
        node.setCode(entity.getCode());
        node.setEntityType(entity.getEntityType());
        node.setSubtype(entity.getEntitySubtype());
        node.setStatus(entity.getStatus() == null ? null : String.valueOf(entity.getStatus()));
        return node;
    }

    private static BusinessEntityGraphRespVO.Edge edge(String source, String target, String type) {
        BusinessEntityGraphRespVO.Edge edge = new BusinessEntityGraphRespVO.Edge();
        edge.setSource(source);
        edge.setTarget(target);
        edge.setRelationType(type);
        edge.setLabel(LABELS.getOrDefault(type, type));
        return edge;
    }
}
