package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.*;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;
import static cn.iocoder.yudao.module.modeling.service.businessentity.BusinessEntityRelationPolicyTest.*;

class BusinessEntityServiceTest {
    private final BusinessEntityMapper entities = mock(BusinessEntityMapper.class);
    private final BusinessEntityRelationMapper edges = mock(BusinessEntityRelationMapper.class);
    private final DataDomainMapper boards = mock(DataDomainMapper.class);
    private final SubjectDomainMapper subjects = mock(SubjectDomainMapper.class);
    private final AdminUserApi users = mock(AdminUserApi.class);
    private final ModelHierarchyResolver hierarchy = mock(ModelHierarchyResolver.class);
    private final com.mzt.logapi.service.ILogRecordService audit = mock(com.mzt.logapi.service.ILogRecordService.class);
    private final BusinessEntityVersionService versions = mock(BusinessEntityVersionService.class);
    private final BusinessEntityServiceImpl service = new BusinessEntityServiceImpl();

    @BeforeEach void setup() {
        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(
                new cn.iocoder.yudao.framework.security.core.LoginUser().setId(99L), new org.springframework.mock.web.MockHttpServletRequest());
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BusinessEntityDO.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BusinessEntityRelationDO.class);
        ReflectionTestUtils.setField(service, "entities", entities);
        ReflectionTestUtils.setField(service, "relations", edges);
        ReflectionTestUtils.setField(service, "boards", boards);
        ReflectionTestUtils.setField(service, "subjects", subjects);
        ReflectionTestUtils.setField(service, "hierarchy", hierarchy);
        ReflectionTestUtils.setField(service, "users", users);
        ReflectionTestUtils.setField(service, "logRecordService", audit);
        ReflectionTestUtils.setField(service, "versions", versions);
        when(boards.selectForUpdate(1L)).thenReturn(DataDomainDO.builder().id(1L).status(0).build());
        when(entities.selectByBoard(1L)).thenReturn(List.of());
        when(edges.selectByBoard(1L)).thenReturn(List.of());
    }

    @AfterEach void clearSecurity() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test void creationDefaultsOfflineAndResolvesPhoneBeforeTakingLock() {
        var request = request("OBJECT", "NORMAL"); request.setLeaderUserId(9L);
        var person = new AdminUserRespDTO(); person.setMobile("00000000000");
        when(users.validateUserList(List.of(9L))).thenReturn(CommonResult.success(true));
        when(users.getUser(9L)).thenReturn(CommonResult.success(person));
        when(entities.insert(any(BusinessEntityDO.class))).thenAnswer(call -> {
            var row = (BusinessEntityDO) call.getArgument(0);
            assertEquals(1, row.getStatus()); assertEquals(0L, row.getRevision()); assertEquals("00000000000", row.getLeaderPhone());
            row.setId(8L); return 1;
        });
        assertEquals(8L, service.create(request));
        var order = inOrder(users, boards);
        order.verify(users).validateUserList(List.of(9L));
        order.verify(users).getUser(9L);
        order.verify(boards).selectForUpdate(1L);
        verify(hierarchy).validateSubjectDomain(10L, null, 1L);
    }

    @Test void staleRevisionAndOnlineEditsAreRejected() {
        var current = entity(1, "NORMAL");
        when(entities.selectById(1L)).thenReturn(current);
        var request = BeanUtils.toBean(request("OBJECT", "NORMAL"), BusinessEntityUpdateReqVO.class);
        request.setId(1L); request.setExpectedRevision(1L);
        assertServiceException(() -> service.update(request), BUSINESS_ENTITY_REVISION_CONFLICT);
        request.setExpectedRevision(0L); current.setStatus(0);
        assertServiceException(() -> service.update(request), BUSINESS_ENTITY_ONLINE);
        verify(entities, never()).updateById(any(BusinessEntityDO.class));
    }

    @Test void boardAndSubtypeCannotBeChanged() {
        when(entities.selectById(1L)).thenReturn(entity(1, "NORMAL"));
        var request = BeanUtils.toBean(request("OBJECT", "HIERARCHICAL"), BusinessEntityUpdateReqVO.class);
        request.setId(1L); request.setExpectedRevision(0L);
        assertServiceException(() -> service.update(request), BUSINESS_ENTITY_TYPE_IMMUTABLE);
    }

    @Test void changingSubjectRemovesOldOutgoingInheritanceBeforeUpdatingOwner() {
        when(entities.selectById(1L)).thenReturn(entity(1, "NORMAL"));
        when(edges.selectByBoard(1L)).thenReturn(List.of(edge(1, 2, "INHERITANCE")));
        var request = BeanUtils.toBean(request("OBJECT", "NORMAL"), BusinessEntityUpdateReqVO.class);
        request.setId(1L); request.setExpectedRevision(0L); request.setSubjectDomainId(11L);
        service.update(request);
        var order = inOrder(edges, entities);
        order.verify(edges).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        order.verify(entities).updateById(any(BusinessEntityDO.class));
        verify(edges, never()).insert(any(BusinessEntityRelationDO.class));
    }

    @Test void referencedEntityCannotBeDeleted() {
        when(entities.selectById(1L)).thenReturn(entity(1, "NORMAL"));
        // Lambda 实例不是稳定匹配键，按方法参数类型匹配实际调用。
        doReturn(List.of(edge(2, 1, "ASSOCIATION"))).when(edges).selectList(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class), eq(1L));
        assertServiceException(() -> service.delete(1L, 0L), BUSINESS_ENTITY_REFERENCED);
        verify(entities, never()).deleteById(anyLong());
    }

    @Test void batchChecksEveryRevisionBeforeAnyStatusMutation() {
        when(entities.selectById(1L)).thenReturn(entity(1, "NORMAL"));
        when(entities.selectById(2L)).thenReturn(entity(2, "NORMAL"));
        var batch = new BusinessEntityBatchReqVO(); batch.setStatus(0);
        var first = new BusinessEntityBatchReqVO.Target(); first.setId(1L); first.setExpectedRevision(0L);
        var second = new BusinessEntityBatchReqVO.Target(); second.setId(2L); second.setExpectedRevision(9L);
        batch.setTargets(List.of(first, second));
        assertServiceException(() -> service.updateStatusBatch(batch), BUSINESS_ENTITY_REVISION_CONFLICT);
        verify(entities, never()).update(any(), any());
    }

    @Test void optionsExcludeSelfAndReleaseOwnProcessReservations() {
        when(boards.selectById(1L)).thenReturn(DataDomainDO.builder().id(1L).build());
        when(entities.selectById(1L)).thenReturn(entity(1, "PROCESS"));
        when(entities.selectByBoard(1L)).thenReturn(List.of(entity(1, "PROCESS"), entity(2, "EVENT"), entity(3, "EVENT")));
        when(edges.selectByBoard(1L)).thenReturn(List.of(edge(1, 2, "PROCESS_EVENT"), edge(8, 3, "PROCESS_EVENT")));
        var result = service.options(1L, 1L);
        assertEquals(List.of(2L, 3L), result.getEntities().stream().map(BusinessEntityRespVO.Summary::getId).toList());
        assertEquals(List.of(3L), result.getUnavailableProcessEventIds());
    }

    @Test void treePagePaginatesRootsAndKeepsAllOrderedEventsWithTheirProcess() {
        var process = entity(75, "PROCESS");
        var trigger = entity(71, "EVENT");
        var start = entity(73, "EVENT");
        var end = entity(74, "EVENT");
        var object = entity(72, "NORMAL");
        start.setRevision(8L);
        var matched = List.of(process, end, start, object, trigger);
        when(entities.selectList(any(BusinessEntityPageReqVO.class))).thenReturn(matched);
        when(entities.selectByBoard(1L)).thenReturn(matched);
        when(edges.selectByBoard(1L)).thenReturn(List.of(
                orderedEdge(75, 74, 2), orderedEdge(75, 71, 0), orderedEdge(75, 73, 1)));
        var request = new BusinessEntityPageReqVO();
        request.setDataDomainId(1L); request.setPageSize(1);

        var first = service.treePage(request);
        assertEquals(2L, first.getTotal());
        assertEquals(List.of(75L), first.getList().stream().map(BusinessEntityRespVO::getId).toList());
        var children = first.getList().getFirst().getChildren();
        assertEquals(List.of(71L, 73L, 74L), children.stream().map(BusinessEntityRespVO::getId).toList());
        assertEquals(8L, children.get(1).getRevision());
        assertEquals("Entity_73", children.get(1).getCode());
        assertEquals(1, children.get(1).getStatus());
        assertEquals(List.of(71L, 73L, 74L), first.getList().getFirst().getProcessEventIds());

        request.setPageNo(2);
        var second = service.treePage(request);
        assertEquals(2L, second.getTotal());
        assertEquals(List.of(72L), second.getList().stream().map(BusinessEntityRespVO::getId).toList());
        assertTrue(second.getList().getFirst().getChildren().isEmpty());
        request.setPageNo(3);
        assertTrue(service.treePage(request).getList().isEmpty());
    }

    @Test void treePageLeavesMatchingEventAtRootWhenParentDoesNotMatch() {
        var process = entity(75, "PROCESS");
        var trigger = entity(71, "EVENT");
        when(entities.selectList(any(BusinessEntityPageReqVO.class))).thenReturn(List.of(trigger));
        when(entities.selectByBoard(1L)).thenReturn(List.of(process, trigger));
        when(edges.selectByBoard(1L)).thenReturn(List.of(orderedEdge(75, 71, 0)));
        var request = new BusinessEntityPageReqVO();
        request.setDataDomainId(1L); request.setKeyword("trigger"); request.setEntitySubtypes(List.of("EVENT"));
        request.setStatus(1); request.setSubjectDomainId(10L); request.setSortOrder("asc");

        var result = service.treePage(request);
        assertEquals(1L, result.getTotal());
        assertEquals(71L, result.getList().getFirst().getId());
        assertTrue(result.getList().getFirst().getChildren().isEmpty());
        verify(entities).selectList(same(request));
    }

    @Test void treePageNestsOnlyMatchingEventsAndIgnoresOtherRelationKinds() {
        var process = entity(75, "PROCESS");
        var trigger = entity(71, "EVENT");
        var start = entity(73, "EVENT");
        var object = entity(72, "NORMAL");
        var base = entity(69, "NORMAL");
        var prior = entity(68, "PROCESS");
        var matched = List.of(process, object, trigger, base, prior);
        when(entities.selectList(any(BusinessEntityPageReqVO.class))).thenReturn(matched);
        when(entities.selectByBoard(1L)).thenReturn(List.of(process, trigger, start, object, base, prior));
        when(edges.selectByBoard(1L)).thenReturn(List.of(
                orderedEdge(75, 71, 0), orderedEdge(75, 73, 1),
                edge(75, 72, "ASSOCIATION"), edge(72, 69, "INHERITANCE"), edge(75, 68, "PREDECESSOR")));

        var result = service.treePage(new BusinessEntityPageReqVO());
        assertEquals(4L, result.getTotal());
        assertEquals(List.of(75L, 72L, 69L, 68L), result.getList().stream().map(BusinessEntityRespVO::getId).toList());
        assertEquals(List.of(71L), result.getList().getFirst().getChildren().stream()
                .map(BusinessEntityRespVO::getId).toList());
        assertEquals(List.of(71L, 73L), result.getList().getFirst().getProcessEventIds());
    }

    @Test void emptyTreePageDoesNotLoadRelationsAndFlatPageKeepsItsContract() {
        var request = new BusinessEntityPageReqVO();
        when(entities.selectList(request)).thenReturn(List.of());
        assertEquals(0L, service.treePage(request).getTotal());
        verifyNoInteractions(edges);
        var process = entity(75, "PROCESS");
        var event = entity(71, "EVENT");
        when(entities.selectPage(request)).thenReturn(
                new cn.iocoder.yudao.framework.common.pojo.PageResult<>(List.of(process, event), 2L));
        var result = service.page(request);
        assertEquals(List.of(75L, 71L), result.getList().stream().map(BusinessEntityRespVO::getId).toList());
        assertNull(result.getList().getFirst().getChildren());
        verifyNoInteractions(edges);
    }

    @Test void treePageUsesTheExistingReadPermission() throws Exception {
        var method = cn.iocoder.yudao.module.modeling.controller.admin.businessentity.BusinessEntityController.class
                .getMethod("treePage", BusinessEntityPageReqVO.class);
        assertEquals("@ss.hasPermission('modeling:business-entity:query')",
                method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value());
    }

    private static BusinessEntityRelationDO orderedEdge(long process, long event, int order) {
        var relation = edge(process, event, "PROCESS_EVENT");
        relation.setSortOrder(order);
        return relation;
    }

    @Test void statusAcceptsOnlyTwoStatesAndLocksAllMutations() throws Exception {
        when(entities.selectById(1L)).thenReturn(entity(1, "NORMAL"));
        var request = new BusinessEntityStatusReqVO(); request.setId(1L); request.setExpectedRevision(0L); request.setStatus(2);
        assertServiceException(() -> service.updateStatus(request), BUSINESS_ENTITY_STATUS_INVALID);
        for (var method : BusinessEntityServiceImpl.class.getMethods()) {
            if (Set.of("create", "update", "updateStatus", "updateStatusBatch", "delete", "deleteBatch").contains(method.getName())) {
                assertNotNull(method.getAnnotation(Transactional.class), method.getName());
            }
        }
    }

    @Test void beanValidationRejectsNullMembersAndMalformedCodes() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var request = request("OBJECT", "NORMAL");
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.setCode("1_bad");
            request.setAssociatedEntityIds(Arrays.asList((Long) null));
            var fields = factory.getValidator().validate(request).stream().map(v -> v.getPropertyPath().toString()).toList();
            assertTrue(fields.contains("code"));
            assertTrue(fields.stream().anyMatch(s -> s.startsWith("associatedEntityIds[0]")));
        }
    }

    @Test void statusAuditIsDeferredUntilCommitAndOmittedOnRollback() {
        var row = entity(1, "NORMAL"); row.setStatus(0);
        when(entities.selectById(1L)).thenReturn(row);
        var request = new BusinessEntityStatusReqVO(); request.setId(1L); request.setExpectedRevision(0L); request.setStatus(1);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateStatus(request);
            verifyNoInteractions(audit);
            var callbacks = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, callbacks.size());
            callbacks.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            var record = org.mockito.ArgumentCaptor.forClass(com.mzt.logapi.beans.LogRecord.class);
            verify(audit).record(record.capture());
            assertEquals("1", record.getValue().getBizNo());
            var extra = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseTree(record.getValue().getExtra());
            assertEquals(0, extra.get("beforeStatus").asInt()); assertEquals(1, extra.get("afterStatus").asInt());
            assertTrue(extra.has("actorId")); assertTrue(extra.has("correlation"));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        reset(audit);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateStatus(request);
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(callback -> callback.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(audit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test void goingOnlineRecordsVersionWithOwnRelationsAndGoingOfflineDoesNot() {
        var row = entity(1, "NORMAL");
        var target = entity(2, "NORMAL");
        var association = edge(1, 2, "ASSOCIATION");
        when(entities.selectById(1L)).thenReturn(row);
        when(entities.selectByBoard(1L)).thenReturn(List.of(row, target));
        when(edges.selectByBoard(1L)).thenReturn(List.of(association, edge(2, 1, "ASSOCIATION")));
        var online = new BusinessEntityStatusReqVO(); online.setId(1L); online.setExpectedRevision(0L); online.setStatus(0);

        service.updateStatus(online);

        verify(versions).recordOnline(same(row), eq(1L), eq(List.of(association)), eq(List.of(row, target)),
                eq(99L), any(java.time.LocalDateTime.class));

        reset(versions);
        var current = entity(1, "NORMAL"); current.setStatus(0); current.setRevision(1L);
        when(entities.selectById(1L)).thenReturn(current);
        var offline = new BusinessEntityStatusReqVO(); offline.setId(1L); offline.setExpectedRevision(1L); offline.setStatus(1);
        service.updateStatus(offline);
        verifyNoInteractions(versions);
    }

    @Test void deletingEntityAlsoRetiresItsVersions() {
        when(entities.selectById(1L)).thenReturn(entity(1, "NORMAL"));
        service.delete(1L, 0L);
        verify(versions).deleteByEntityId(1L);
    }
}
