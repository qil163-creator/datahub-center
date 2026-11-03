package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntitySaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

class BusinessEntityRelationPolicyTest {
    @Test void processRequiresTwoDistinctAvailableEvents() {
        var request = request("ACTIVITY", "PROCESS");
        request.setProcessEventIds(List.of(2L));
        assertInvalid(() -> validate(1L, request, List.of(entity(2, "EVENT")), List.of()));
        request.setProcessEventIds(List.of(2L, 2L));
        assertInvalid(() -> validate(1L, request, List.of(entity(2, "EVENT")), List.of()));
        request.setProcessEventIds(List.of(2L, 3L));
        assertInvalid(() -> validate(1L, request, List.of(entity(2, "EVENT"), entity(3, "EVENT")),
                List.of(edge(9, 2, "PROCESS_EVENT"))));
        assertEquals(2, validate(1L, request, List.of(entity(2, "EVENT"), entity(3, "EVENT")), List.of()).size());
        assertEquals(2, validate(1L, request, List.of(entity(2, "EVENT"), entity(3, "EVENT")),
                List.of(edge(1, 2, "PROCESS_EVENT"))).size());
    }

    @Test void processAllowsAssociationsAndExclusivePredecessor() {
        var request = request("ACTIVITY", "PROCESS");
        request.setProcessEventIds(List.of(2L, 3L));
        request.setPredecessorEntityIds(List.of(4L));
        request.setAssociatedEntityIds(List.of(5L));
        var targets = List.of(entity(2, "EVENT"), entity(3, "EVENT"), entity(4, "PROCESS"), entity(5, "NORMAL"));
        assertEquals(4, validate(1L, request, targets, List.of()).size());
        assertInvalid(() -> validate(1L, request, targets, List.of(edge(8, 4, "PREDECESSOR"))));
        assertInvalid(() -> validate(1L, request, targets, List.of(edge(4, 1, "PREDECESSOR"))));
    }

    @Test void inheritedEntitiesMustBeInSameSubjectAndCannotCreateCycles() {
        var request = request("OBJECT", "NORMAL");
        request.setInheritEntityId(2L);
        var target = entity(2, "NORMAL");
        target.setSubjectDomainId(99L);
        assertInvalid(() -> validate(1L, request, List.of(target), List.of()));
        target.setSubjectDomainId(10L);
        assertInvalid(() -> validate(1L, request, List.of(target), List.of(edge(2, 3, "INHERITANCE"), edge(3, 1, "INHERITANCE"))));
        assertEquals(1, validate(1L, request, List.of(target), List.of()).size());
        request.setAssociatedEntityIds(List.of(2L));
        assertInvalid(() -> validate(1L, request, List.of(target), List.of()));
    }

    @Test void subtypeAndBoardConstraintsCannotBeBypassed() {
        var request = request("OBJECT", "HIERARCHICAL");
        request.setAssociatedEntityIds(List.of(2L));
        assertInvalid(() -> validate(1L, request, List.of(entity(2, "NORMAL")), List.of()));
        request.setEntitySubtype("EVENT");
        assertInvalid(() -> validate(1L, request, List.of(), List.of()));
        request.setEntityType("ACTIVITY");
        var otherBoard = entity(2, "NORMAL"); otherBoard.setDataDomainId(90L);
        assertInvalid(() -> validate(1L, request, List.of(otherBoard), List.of()));
        request.setAssociatedEntityIds(List.of(1L));
        assertInvalid(() -> validate(1L, request, List.of(entity(1, "EVENT")), List.of()));
    }

    @Test void incomingInheritancePreventsCrossSubjectMove() {
        var request = request("OBJECT", "NORMAL");
        request.setSubjectDomainId(11L);
        assertInvalid(() -> validate(1L, request, List.of(entity(2, "NORMAL")), List.of(edge(2, 1, "INHERITANCE"))));
    }

    @Test void orderedEventIdsArePreservedAndMissingReferencesAreRejected() {
        var request = request("ACTIVITY", "PROCESS");
        request.setProcessEventIds(List.of(3L, 2L));
        var rows = validate(1L, request, List.of(entity(2, "EVENT"), entity(3, "EVENT")), List.of());
        assertEquals(List.of(3L, 2L), rows.stream().map(BusinessEntityRelationDO::getTargetEntityId).toList());
        assertEquals(List.of(0, 1), rows.stream().map(BusinessEntityRelationDO::getSortOrder).toList());
        assertInvalid(() -> validate(1L, request, List.of(entity(2, "EVENT")), List.of()));
    }

    private static List<BusinessEntityRelationDO> validate(Long id, BusinessEntitySaveReqVO request,
            List<BusinessEntityDO> rows, List<BusinessEntityRelationDO> edges) {
        return BusinessEntityRelationPolicy.validate(id, request, rows, edges);
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable call) {
        var error = assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, call);
        assertEquals(BUSINESS_ENTITY_RELATION_INVALID.getCode(), error.getCode());
    }

    static BusinessEntitySaveReqVO request(String type, String subtype) {
        var request = new BusinessEntitySaveReqVO();
        request.setDataDomainId(1L); request.setSubjectDomainId(10L); request.setName("测试实体"); request.setCode("Test_entity");
        request.setEntityType(type); request.setEntitySubtype(subtype);
        return request;
    }

    static BusinessEntityDO entity(long id, String subtype) {
        var row = new BusinessEntityDO();
        row.setId(id); row.setDataDomainId(1L); row.setSubjectDomainId(10L);
        row.setEntityType(List.of("EVENT", "PROCESS").contains(subtype) ? "ACTIVITY" : "OBJECT");
        row.setEntitySubtype(subtype); row.setStatus(1); row.setRevision(0L); row.setName("实体" + id); row.setCode("Entity_" + id);
        return row;
    }

    static BusinessEntityRelationDO edge(long source, long target, String type) {
        var edge = new BusinessEntityRelationDO();
        edge.setSourceEntityId(source); edge.setTargetEntityId(target); edge.setRelationType(type); edge.setSortOrder(0);
        return edge;
    }
}
