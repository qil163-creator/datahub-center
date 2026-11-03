package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain.*;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.subjectdomain.*;
import cn.iocoder.yudao.module.modeling.convert.planning.PlanningDomainConvert;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.*;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

class PlanningDomainServiceTest {
    private final SubjectDomainMapper subjects = mock(SubjectDomainMapper.class);
    private final DataDomainMapper boards = mock(DataDomainMapper.class);
    private final AdminUserApi users = mock(AdminUserApi.class);
    private final SubjectDomainServiceImpl service = new SubjectDomainServiceImpl();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "subjectDomainMapper", subjects);
        ReflectionTestUtils.setField(service, "dataDomainMapper", boards);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        when(users.validateUserList(any())).thenReturn(CommonResult.success(true));
        when(users.getUser(anyLong())).thenReturn(CommonResult.success(new AdminUserRespDTO()));
        when(boards.selectForUpdate(10L)).thenReturn(DataDomainDO.builder().id(10L).status(0).build());
    }

    @Test
    void crossBoardParentIsRejected() {
        when(subjects.selectById(2L)).thenReturn(SubjectDomainDO.builder().id(2L).parentId(0L).dataDomainId(11L).build());
        assertServiceException(() -> service.createSubjectDomain(request(2L)), SUBJECT_DOMAIN_BOARD_MISMATCH);
        verify(subjects, never()).insert(any(SubjectDomainDO.class));
    }

    @Test
    void cyclicAndSixthLevelParentsAreRejected() {
        when(subjects.selectById(2L)).thenReturn(SubjectDomainDO.builder().id(2L).parentId(2L).dataDomainId(10L).build());
        assertServiceException(() -> service.createSubjectDomain(request(2L)), SUBJECT_DOMAIN_PARENT_ERROR);
        for (long id = 1; id <= 5; id++) {
            when(subjects.selectById(id)).thenReturn(SubjectDomainDO.builder().id(id).parentId(id - 1).dataDomainId(10L).build());
        }
        assertServiceException(() -> service.createSubjectDomain(request(5L)), SUBJECT_DOMAIN_DEPTH_EXCEEDED);
    }

    @Test
    void enabledThemeCannotBeEditedAndCreationDefaultsDisabled() {
        var req = request(0L);
        req.setId(3L);
        when(subjects.selectById(3L)).thenReturn(SubjectDomainDO.builder().id(3L).parentId(0L).dataDomainId(10L).status(0).build());
        assertServiceException(() -> service.updateSubjectDomain(req), SUBJECT_DOMAIN_ENABLED);
        req.setId(null);
        when(subjects.insert(any(SubjectDomainDO.class))).thenAnswer(call -> {
            SubjectDomainDO row = call.getArgument(0);
            assertEquals(1, row.getStatus());
            row.setId(8L);
            return 1;
        });
        assertEquals(8L, service.createSubjectDomain(req));
    }

    @Test
    void disabledBoardCannotAcceptNewThemes() {
        when(boards.selectForUpdate(10L)).thenReturn(DataDomainDO.builder().id(10L).status(1).build());
        assertServiceException(() -> service.createSubjectDomain(request(0L)), MODEL_DATA_DOMAIN_DISABLED);
    }

    @Test
    void structuredRulesAndLosslessOwnerIdsRoundTrip() {
        var req = new DataDomainSaveReqVO();
        req.setBusinessOwnerIds(List.of(9007199254740993L));
        req.setDataOwnerIds(List.of(9007199254740995L));
        req.getNamingRules().get(1).setDaySuffix("_daily");
        var stored = PlanningDomainConvert.toDataObject(req);
        assertTrue(stored.getNamingRules().contains("_daily"));
        var result = PlanningDomainConvert.toResponse(stored);
        assertEquals(req.getNamingRules(), result.getNamingRules());
        assertEquals(req.getBusinessOwnerIds(), result.getBusinessOwnerIds());
        result.getBusinessOwnerIds().clear();
        assertEquals(1, req.getBusinessOwnerIds().size());
    }

    @Test
    void validationRejectsInvalidStatusOversizedOwnersAndUnsafeCodes() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var req = new DataDomainSaveReqVO();
            req.setName("板块"); req.setCode("BOARD"); req.setLeaderUserId(1L);
            assertTrue(factory.getValidator().validate(req).isEmpty());
            req.setStatus(2); req.setCode("bad-name"); req.setBusinessOwnerIds(List.of(1L, 2L, 3L, 4L));
            var paths = factory.getValidator().validate(req).stream().map(error -> error.getPropertyPath().toString()).toList();
            assertTrue(paths.containsAll(List.of("status", "code", "businessOwnerIds")));
        }
    }

    @Test
    void rejectsRulesOutsideTheEditableMatrixBeforeAnyRpcOrWrite() {
        var boardsService = new DataDomainServiceImpl();
        ReflectionTestUtils.setField(boardsService, "dataDomainMapper", boards);
        ReflectionTestUtils.setField(boardsService, "adminUserApi", users);
        var mutations = List.<java.util.function.Consumer<List<DataDomainNamingRuleVO>>>of(
                rules -> rules.get(0).setPrefix("custom_"),
                rules -> rules.get(1).setPrefix("ods_"),
                rules -> rules.get(0).setIncludeSubjectAbbreviation(true),
                rules -> rules.get(3).setIncludeSubjectAbbreviation(true),
                rules -> rules.get(0).setDaySuffix("_daily"),
                rules -> rules.set(0, null));
        for (var mutate : mutations) {
            var request = new DataDomainSaveReqVO();
            mutate.accept(request.getNamingRules());
            assertServiceException(() -> boardsService.createDataDomain(request), DATA_DOMAIN_NAMING_RULE_INVALID);
        }
        verifyNoInteractions(users, boards);
    }

    @Test
    void processHourlyAndSummaryDailySuffixesRemainEditable() {
        var boardsService = new DataDomainServiceImpl();
        ReflectionTestUtils.setField(boardsService, "dataDomainMapper", boards);
        ReflectionTestUtils.setField(boardsService, "adminUserApi", users);
        var request = new DataDomainSaveReqVO();
        request.setName("板块"); request.setCode("BOARD"); request.setLeaderUserId(1L);
        request.getNamingRules().get(1).setHourSuffix("_hourly");
        request.getNamingRules().get(1).setMinuteSuffix("_minute");
        request.getNamingRules().get(3).setDaySuffix("_daily");
        assertDoesNotThrow(() -> boardsService.createDataDomain(request));
        verify(boards).insert(any(DataDomainDO.class));
    }

    @Test
    void containerValidationRejectsNullMembersAndAllowsOmittedOptionalOwnerLists() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new DataDomainSaveReqVO();
            request.setName("板块"); request.setCode("BOARD"); request.setLeaderUserId(1L);
            request.setBusinessOwnerIds(null); request.setDataOwnerIds(null);
            request.getNamingRules().get(2).setHourSuffix("x".repeat(512));
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.getNamingRules().get(2).setHourSuffix("x".repeat(513));
            assertTrue(factory.getValidator().validate(request).stream().anyMatch(error -> error.getPropertyPath().toString().endsWith("hourSuffix")));
            request.getNamingRules().get(2).setHourSuffix("_T+h");
            assertTrue(factory.getValidator().validate(request).isEmpty());
            assertEquals(List.of(), PlanningDomainConvert.toDataObject(request).getBusinessOwnerIds());
            request.setBusinessOwnerIds(java.util.Arrays.asList((Long) null));
            request.setDataOwnerIds(java.util.Arrays.asList((Long) null));
            request.getNamingRules().set(0, null);
            var paths = factory.getValidator().validate(request).stream().map(error -> error.getPropertyPath().toString()).toList();
            assertTrue(paths.stream().anyMatch(path -> path.startsWith("businessOwnerIds[0]")));
            assertTrue(paths.stream().anyMatch(path -> path.startsWith("dataOwnerIds[0]")));
            assertTrue(paths.stream().anyMatch(path -> path.startsWith("namingRules[0]")));
        }
    }

    private static SubjectDomainSaveReqVO request(Long parentId) {
        var request = new SubjectDomainSaveReqVO();
        request.setDataDomainId(10L); request.setParentId(parentId); request.setName("主题");
        request.setCode("THEME"); request.setAbbreviation("TH"); request.setLeaderUserId(1L);
        return request;
    }

    @Test
    void enabledBoardCanUpdateSixRulesWithoutWritingOwnerColumns() {
        var service = new DataDomainServiceImpl();
        ReflectionTestUtils.setField(service, "dataDomainMapper", boards);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        var request = new DataDomainNamingRulesReqVO(); request.setId(10L);
        request.setNamingRules(DataDomainNamingRuleVO.detailDefaults());
        request.getNamingRules().get(1).setDaySuffix("_dim_daily");
        service.updateNamingRules(request);
        verify(boards).updateNamingRules(eq(10L), contains("_dim_daily"), anyString());
        verify(boards, never()).updateById(any(DataDomainDO.class));
        verifyNoInteractions(users);
        request.getNamingRules().getLast().setHourSuffix("_hour");
        assertServiceException(() -> service.updateNamingRules(request), DATA_DOMAIN_NAMING_RULE_INVALID);
    }

    @Test
    void fourRuleGeneralEditPreservesDetailDimensionConfiguration() {
        var service = new DataDomainServiceImpl();
        ReflectionTestUtils.setField(service, "dataDomainMapper", boards);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        var rules = DataDomainNamingRuleVO.detailDefaults(); rules.get(1).setDaySuffix("_configured");
        when(boards.selectForUpdate(10L)).thenReturn(DataDomainDO.builder().id(10L).status(1)
                .namingRules(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(rules)).build());
        var request = new DataDomainSaveReqVO(); request.setId(10L); request.setName("板块"); request.setCode("BOARD"); request.setLeaderUserId(1L);
        service.updateDataDomain(request);
        var saved = org.mockito.ArgumentCaptor.forClass(DataDomainDO.class);
        verify(boards).updateById(saved.capture());
        var response = PlanningDomainConvert.toResponse(saved.getValue());
        assertEquals(6, response.getNamingRules().size());
        assertEquals("_configured", response.getNamingRules().get(1).getDaySuffix());
    }

    @Test
    void readOnlyRuleValuesRoundTripAndCannotBeOverwritten() {
        var service = new DataDomainServiceImpl();
        ReflectionTestUtils.setField(service, "dataDomainMapper", boards);
        var rules = DataDomainNamingRuleVO.detailDefaults();
        rules.get(0).setMinuteSuffix("fgfasdf");
        when(boards.selectForUpdate(10L)).thenReturn(DataDomainDO.builder().id(10L).status(0)
                .namingRules(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(rules)).build());
        var request = new DataDomainNamingRulesReqVO(); request.setId(10L); request.setNamingRules(rules);
        service.updateNamingRules(request);
        verify(boards).updateNamingRules(eq(10L), contains("fgfasdf"), anyString());
        rules.get(0).setMinuteSuffix("");
        assertServiceException(() -> service.updateNamingRules(request), DATA_DOMAIN_NAMING_RULE_INVALID);
        rules.get(0).setMinuteSuffix("fgfasdf"); rules.get(0).setPrefix("other_");
        assertServiceException(() -> service.updateNamingRules(request), DATA_DOMAIN_NAMING_RULE_INVALID);
        rules.get(0).setPrefix("ods_"); rules.get(0).setIncludeSubjectAbbreviation(true);
        assertServiceException(() -> service.updateNamingRules(request), DATA_DOMAIN_NAMING_RULE_INVALID);
    }
}
