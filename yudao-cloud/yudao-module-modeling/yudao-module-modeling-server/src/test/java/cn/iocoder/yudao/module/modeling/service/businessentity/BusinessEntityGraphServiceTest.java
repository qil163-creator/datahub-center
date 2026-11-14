package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityRelationMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.LogicModelMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityRelationPolicyTest.edge;
import static cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityRelationPolicyTest.entity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 业务实体关系图：一跳范围的出入边、逻辑表节点与数量上限。 */
@ExtendWith(MockitoExtension.class)
class BusinessEntityGraphServiceTest {

    @Mock private BusinessEntityMapper entityMapper;
    @Mock private BusinessEntityRelationMapper relationMapper;
    @Mock private LogicModelMapper modelMapper;
    @InjectMocks private BusinessEntityGraphService service;

    @Test
    void includesIncomingAndOutgoingRelationsAndLogicModels() {
        var center = entity(1, "NORMAL");
        when(entityMapper.selectById(1L)).thenReturn(center);
        when(entityMapper.selectByBoard(1L)).thenReturn(List.of(center, entity(2, "NORMAL"), entity(3, "NORMAL"),
                entity(4, "NORMAL")));
        when(relationMapper.selectByBoard(1L)).thenReturn(List.of(edge(1, 2, "INHERITANCE"),
                edge(3, 1, "ASSOCIATION"), edge(3, 4, "ASSOCIATION")));
        LogicModelDO model = new LogicModelDO();
        model.setId(9L);
        model.setName("客户维度表");
        model.setTableNameNotation("dim_customer");
        model.setTableType("DIMENSION");
        when(modelMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(model));

        var graph = service.graph(1L);

        assertEquals("ENTITY:1", graph.getCenterKey());
        assertEquals(List.of("ENTITY:1", "ENTITY:2", "ENTITY:3", "MODEL:9"),
                graph.getNodes().stream().map(node -> node.getKey()).toList(), "不相邻的实体 4 不出现");
        assertEquals(List.of("继承", "关联", "逻辑表"), graph.getEdges().stream().map(edge -> edge.getLabel()).toList());
        assertEquals("ENTITY:3", graph.getEdges().get(1).getSource(), "入边保留原方向");
        assertEquals("dim_customer", graph.getNodes().get(3).getCode());
        assertFalse(graph.getModelsTruncated());
    }

    @Test
    void truncatesLogicModelsBeyondLimit() {
        var center = entity(1, "NORMAL");
        when(entityMapper.selectById(1L)).thenReturn(center);
        when(entityMapper.selectByBoard(1L)).thenReturn(List.of(center));
        when(relationMapper.selectByBoard(1L)).thenReturn(List.of());
        List<LogicModelDO> models = new ArrayList<>();
        LongStream.rangeClosed(1, BusinessEntityGraphService.MAX_MODELS + 1).forEach(id -> {
            LogicModelDO model = new LogicModelDO();
            model.setId(id);
            models.add(model);
        });
        when(modelMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(models);

        var graph = service.graph(1L);

        assertTrue(graph.getModelsTruncated());
        assertEquals(BusinessEntityGraphService.MAX_MODELS + 1, graph.getNodes().size());
    }
}
