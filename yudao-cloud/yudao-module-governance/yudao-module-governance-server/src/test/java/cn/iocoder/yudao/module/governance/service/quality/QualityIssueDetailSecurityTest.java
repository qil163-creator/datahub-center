package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityIssueDataDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityCheckObjectMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityIssueDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_ISSUE_ACCESS_FORBIDDEN;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 问题数据详情必须委托当前用户的受管只读查询，不能直接使用数据源账号回源。 */
class QualityIssueDetailSecurityTest {
    private QualityIssueDataServiceImpl service;
    private QualityIssueDataMapper issues;
    private QualityCheckObjectMapper objects;
    private DatabaseApi database;
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach void setUp() {
        TenantContextHolder.clear();
        TenantContextHolder.setIgnore(false);
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(11L);
        issues = mock(QualityIssueDataMapper.class);
        objects = mock(QualityCheckObjectMapper.class);
        database = mock(DatabaseApi.class);
        service = new QualityIssueDataServiceImpl();
        ReflectionTestUtils.setField(service, "issueDataMapper", issues);
        ReflectionTestUtils.setField(service, "checkObjectMapper", objects);
        ReflectionTestUtils.setField(service, "databaseApi", database);
        var issue = new QualityIssueDataDO();
        issue.setId(1L); issue.setObjectId(2L);
        issue.setLocationSql("SELECT * FROM public.review_rows WHERE id = 3");
        when(issues.selectById(1L)).thenReturn(issue);
        var object = new QualityCheckObjectDO();
        object.setId(2L); object.setDatasourceConnId(9L);
        when(objects.selectById(2L)).thenReturn(object);
    }

    @AfterEach void clear() {
        security.close();
        TenantContextHolder.clear();
    }

    @Test void keepsOnlyAuthorizedMaskedRowsAndPinsTheCurrentActor() {
        var response = new DatabasePreviewRespDTO();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "9007199254740993"); row.put("mobile", "138****0000"); row.put("optional", null);
        response.setRows(List.of(row));
        when(database.executeQuery(any())).thenReturn(CommonResult.success(response));

        assertEquals(List.of(row), service.getIssueDetail(1L, 25));
        var request = ArgumentCaptor.forClass(DatabaseQueryReqDTO.class);
        verify(database).executeQuery(request.capture());
        assertEquals(9L, request.getValue().getDatabaseId());
        assertEquals(25, request.getValue().getLimit());
        assertEquals("SELECT * FROM public.review_rows WHERE id = 3", request.getValue().getSql());
        verifyNoMoreInteractions(database);
    }

    @Test void permissionFailureIsPropagatedWithoutRawQueryFallback() {
        when(database.executeQuery(any())).thenReturn(CommonResult.error(QUALITY_ISSUE_ACCESS_FORBIDDEN));
        var failure = assertThrows(ServiceException.class, () -> service.getIssueDetail(1L, 100));
        assertEquals(QUALITY_ISSUE_ACCESS_FORBIDDEN.getCode(), failure.getCode());
        verify(database).executeQuery(any());
        verifyNoMoreInteractions(database);
    }

    @Test void missingActorCannotReadDetails() {
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.getIssueDetail(1L, 100));
        verifyNoInteractions(issues, objects, database);
    }

    @Test void boundsRequestedRowsAndRejectsAnUnconfirmedProviderResult() {
        when(database.executeQuery(any())).thenReturn(CommonResult.success(null));
        assertThrows(ServiceException.class, () -> service.getIssueDetail(1L, 100_000));
        assertThrows(ServiceException.class, () -> service.getIssueDetail(1L, 0));
        var requests = ArgumentCaptor.forClass(DatabaseQueryReqDTO.class);
        verify(database, times(2)).executeQuery(requests.capture());
        assertEquals(1000, requests.getAllValues().get(0).getLimit());
        assertEquals(1, requests.getAllValues().get(1).getLimit());
    }
}
