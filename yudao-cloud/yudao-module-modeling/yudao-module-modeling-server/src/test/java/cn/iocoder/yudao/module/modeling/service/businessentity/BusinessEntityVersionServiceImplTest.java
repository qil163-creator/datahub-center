package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.ModelDiffRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityVersionDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityRelationMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityVersionMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_VERSION_MISMATCH;
import static cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityRelationPolicyTest.edge;
import static cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityRelationPolicyTest.entity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 业务实体版本：快照内容、版本号递增、属性与关系比对。 */
@ExtendWith(MockitoExtension.class)
class BusinessEntityVersionServiceImplTest {

    @Mock private BusinessEntityVersionMapper versionMapper;
    @Mock private BusinessEntityMapper entityMapper;
    @Mock private BusinessEntityRelationMapper relationMapper;
    @Mock private AdminUserApi adminUserApi;
    @InjectMocks private BusinessEntityVersionServiceImpl service;

    @Test
    void firstOnlineSnapshotsAttributesAndNamedRelations() {
        var row = entity(1, "NORMAL");
        var target = entity(2, "NORMAL");

        service.recordOnline(row, 3L, List.of(edge(1, 2, "ASSOCIATION"), edge(1, 404, "ASSOCIATION")),
                List.of(row, target), 9L, LocalDateTime.of(2026, 11, 14, 9, 0));

        ArgumentCaptor<BusinessEntityVersionDO> saved = ArgumentCaptor.forClass(BusinessEntityVersionDO.class);
        verify(versionMapper).insert(saved.capture());
        assertEquals(1, saved.getValue().getVersionNo());
        assertEquals(3L, saved.getValue().getEntityRevision());
        Map<?, ?> snapshot = JsonUtils.parseObject(saved.getValue().getSnapshot(), Map.class);
        assertEquals("实体1", snapshot.get("name"));
        List<?> relations = (List<?>) snapshot.get("relations");
        assertEquals(1, relations.size(), "对端实体不存在的关系不写入快照");
        assertEquals("实体2", ((Map<?, ?>) relations.getFirst()).get("name"));
        assertEquals(Map.of("initial", true), JsonUtils.parseObject(saved.getValue().getChangeSummary(), Map.class));
    }

    @Test
    void laterOnlineCountsAttributeAndRelationChanges() {
        var row = entity(1, "NORMAL");
        var old = BusinessEntityVersionService.snapshot(row, List.of(edge(1, 2, "ASSOCIATION")),
                Map.of(2L, entity(2, "NORMAL")));
        when(versionMapper.selectLatest(1L)).thenReturn(BusinessEntityVersionDO.builder().id(5L).entityId(1L)
                .versionNo(1).snapshot(JsonUtils.toJsonString(old)).build());
        row.setDescription("新增描述");

        service.recordOnline(row, 2L, List.of(edge(1, 3, "ASSOCIATION")),
                List.of(row, entity(2, "NORMAL"), entity(3, "NORMAL")), 9L, LocalDateTime.now());

        ArgumentCaptor<BusinessEntityVersionDO> saved = ArgumentCaptor.forClass(BusinessEntityVersionDO.class);
        verify(versionMapper).insert(saved.capture());
        assertEquals(2, saved.getValue().getVersionNo());
        Map<?, ?> summary = JsonUtils.parseObject(saved.getValue().getChangeSummary(), Map.class);
        assertEquals(1, summary.get("attributeChanged"));
        assertEquals(1, summary.get("relationAdded"));
        assertEquals(1, summary.get("relationRemoved"));
    }

    @Test
    void diffListsRelationChangesWithLabels() {
        var before = BusinessEntityVersionService.snapshot(entity(1, "NORMAL"), List.of(edge(1, 2, "INHERITANCE")),
                Map.of(2L, entity(2, "NORMAL")));
        var after = BusinessEntityVersionService.snapshot(entity(1, "NORMAL"), List.of(edge(1, 3, "ASSOCIATION")),
                Map.of(3L, entity(3, "NORMAL")));

        var diff = BusinessEntityVersionServiceImpl.diff(before, after);

        assertTrue(diff.getAttributeChanges().isEmpty());
        assertEquals(2, diff.getRelationChanges().size());
        var added = diff.getRelationChanges().getFirst();
        assertEquals(ModelDiffRespVO.ADDED, added.getChangeType());
        assertEquals("关联", added.getRelationLabel());
        assertEquals(3L, added.getEntityId());
        assertEquals("继承", diff.getRelationChanges().get(1).getRelationLabel());
    }

    @Test
    void compareRejectsVersionOfAnotherEntity() {
        when(versionMapper.selectById(5L)).thenReturn(BusinessEntityVersionDO.builder().id(5L).entityId(8L)
                .versionNo(1).snapshot("{}").build());
        ServiceException error = assertThrows(ServiceException.class, () -> service.compare(1L, 5L, null));
        assertEquals(MODEL_VERSION_MISMATCH.getCode(), error.getCode());
        verifyNoInteractions(entityMapper);
    }
}
