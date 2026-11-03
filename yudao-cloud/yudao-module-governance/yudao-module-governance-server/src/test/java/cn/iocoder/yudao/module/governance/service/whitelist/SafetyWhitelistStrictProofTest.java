package cn.iocoder.yudao.module.governance.service.whitelist;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.governance.dal.dataobject.whitelist.SafetyWhitelistDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.subject.SafetyWhitelistSubjectDO;
import cn.iocoder.yudao.module.governance.dal.mysql.whitelist.SafetyWhitelistMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.subject.SafetyWhitelistSubjectMapper;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SafetyWhitelistStrictProofTest {
    private SafetyWhitelistServiceImpl service;
    private SafetyWhitelistMapper lists;
    private SafetyWhitelistSubjectMapper subjects;
    private PermissionApi roles;
    private AdminUserApi users;
    @BeforeEach void setup() {
        service = new SafetyWhitelistServiceImpl(); lists = mock(SafetyWhitelistMapper.class); subjects = mock(SafetyWhitelistSubjectMapper.class);
        roles = mock(PermissionApi.class); users = mock(AdminUserApi.class);
        ReflectionTestUtils.setField(service, "whitelistMapper", lists); ReflectionTestUtils.setField(service, "whitelistSubjectMapper", subjects);
        ReflectionTestUtils.setField(service, "permissionApi", roles); ReflectionTestUtils.setField(service, "adminUserApi", users);
        when(lists.selectActiveList(eq("QUERY"), any())).thenReturn(List.of());
    }
    @Test void emptyAuthoritativeListIsFalseButUnknownListIsNot() {
        assertFalse(service.getMaskExemptStrict(99L, "QUERY").getExempt());
        when(lists.selectActiveList(eq("QUERY"), any())).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.getMaskExemptStrict(99L, "QUERY"));
        assertFalse(service.getMaskExempt(99L, "QUERY").getExempt());
    }
    @Test void roleRpcFailureAndMissingDepartmentFailClosedWithoutLoggingSensitiveCause() {
        activate("ROLE"); when(roles.getUserRoleIdListByRoleIds(any())).thenThrow(new IllegalStateException("private-role-rpc-sentinel"));
        var denied = assertThrows(ServiceException.class, () -> service.getMaskExemptStrict(99L, "QUERY"));
        assertFalse(denied.getMessage().contains("private-")); assertNull(denied.getCause());
        activate("DEPT"); when(users.getUser(99L)).thenReturn(CommonResult.success(null));
        assertThrows(ServiceException.class, () -> service.getMaskExemptStrict(99L, "QUERY"));
    }
    @Test void missingSubjectsAndUnknownSubjectTypeCannotBecomeAProofOfNoExemption() {
        activate("USER"); when(subjects.selectListByWhitelistId(1L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.getMaskExemptStrict(99L, "QUERY"));
        activate("UNRECOGNIZED"); assertThrows(ServiceException.class, () -> service.getMaskExemptStrict(99L, "QUERY"));
    }
    @Test void trueAndFalseSubjectsRemainDistinctAuthoritativeResults() {
        activate("USER"); assertFalse(service.getMaskExemptStrict(99L, "QUERY").getExempt());
        when(subjects.selectListByWhitelistId(1L)).thenReturn(List.of(SafetyWhitelistSubjectDO.builder().subjectId(99L).build()));
        assertTrue(service.getMaskExemptStrict(99L, "QUERY").getExempt());
        activate("ROLE"); when(roles.getUserRoleIdListByRoleIds(any())).thenReturn(CommonResult.success(Set.of(99L)));
        assertTrue(service.getMaskExemptStrict(99L, "QUERY").getExempt());
        when(roles.getUserRoleIdListByRoleIds(any())).thenReturn(CommonResult.success(null));
        assertThrows(ServiceException.class, () -> service.getMaskExemptStrict(99L, "QUERY"));
    }

    @Test void localObjectScopeCanNeverBecomeGlobalAndMalformedScopeIsNotAnAuthoritativeProof() {
        for (String scope : List.of("CATEGORY", "CATALOG")) {
            when(lists.selectActiveList(eq("QUERY"), any())).thenReturn(List.of(SafetyWhitelistDO.builder()
                    .id(1L).subjectType("USER").objectType(scope).objectId(56L).build()));
            assertFalse(service.getMaskExemptStrict(99L, "QUERY").getExempt());
            assertFalse(service.getMaskExempt(99L, "QUERY").getExempt());
        }
        verifyNoInteractions(subjects);
        when(lists.selectActiveList(eq("QUERY"), any())).thenReturn(List.of(SafetyWhitelistDO.builder()
                .id(1L).subjectType("USER").objectType("GLOBAL").objectId(56L).build()));
        assertThrows(ServiceException.class, () -> service.getMaskExemptStrict(99L, "QUERY"));
        assertFalse(service.getMaskExempt(99L, "QUERY").getExempt());
    }
    private void activate(String type) {
        when(lists.selectActiveList(eq("QUERY"), any())).thenReturn(List.of(SafetyWhitelistDO.builder().id(1L).subjectType(type).objectType("GLOBAL").build()));
        when(subjects.selectListByWhitelistId(1L)).thenReturn(List.of(SafetyWhitelistSubjectDO.builder().subjectId(20L).build()));
    }
}
