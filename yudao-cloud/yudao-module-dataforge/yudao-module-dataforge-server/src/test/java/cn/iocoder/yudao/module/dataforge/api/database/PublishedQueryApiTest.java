package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.dataforge.framework.security.PublishedQueryRpcProperties;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import cn.iocoder.yudao.module.supply.api.dataapi.PublishedWriteClaimApi;
import java.math.BigDecimal;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedQueryApiTest {

    private final PublishedWriteClaimApi claims = mock(PublishedWriteClaimApi.class);

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final AccessDatabaseResolver databases = mock(AccessDatabaseResolver.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);

    private PublishedQueryApiImpl service(boolean supplyChannel) throws Exception {
        var leaf = mock(X509Certificate.class);
        when(leaf.getEncoded()).thenReturn(new byte[]{11, 17, 23});
        String pin = VerifiedServicePeerResolver.fingerprint(leaf);
        if (supplyChannel) {
            request.setSecure(true);
            request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{leaf});
        }
        return new PublishedQueryApiImpl(request, new PublishedQueryRpcProperties(Map.of("supply", Set.of(pin))),
                databases, runtime, claims);
    }

    private PublishedQueryApi.Query query(String sql, List<PublishedQueryApi.Bind> params) {
        return new PublishedQueryApi.Query(10L, sql, params, 1, 20, 30, null);
    }

    private void usePostgres() {
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        when(databases.resolveEnabled(10L, DATABASE_NOT_EXISTS)).thenReturn(connection);
    }

    private static final String TICKET = "a".repeat(64);

    @Test void writeRequiresMutualTlsEvenForAuthenticatedHttpSessions() throws Exception {
        var api = service(false);
        assertThrows(ServiceException.class, () -> api.writeProfile(new PublishedQueryApi.Source(10L)));
        assertThrows(ServiceException.class, () -> api.executeClaim(new PublishedQueryApi.Ticket(TICKET)));
        verifyNoInteractions(claims, databases, runtime);
    }

    @Test void multiTenantModeCannotUseSingleTenantReadOrWriteContracts() throws Exception {
        var api = service(true);
        org.springframework.test.util.ReflectionTestUtils.setField(api, "tenantEnabled", true);
        assertThrows(ServiceException.class, () -> api.query(query("SELECT 1", List.of())));
        assertThrows(ServiceException.class, () -> api.executeClaim(new PublishedQueryApi.Ticket(TICKET)));
        verifyNoInteractions(claims, databases, runtime);
    }

    @Test void writeRejectsChangedConnectionOrTargetBeforeExecuting() throws Exception {
        var api = service(true);
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setSchema("public");
        when(databases.resolveEnabled(10L, DATABASE_NOT_EXISTS)).thenReturn(connection);
        var contract = new PublishedWriteClaimApi.Contract(1L, 2L, 3L, 4L, 10L, "published-revision", "INSERT",
                List.of(new PublishedWriteClaimApi.Statement(1, "public", "expected", "INSERT INTO public.other(id) VALUES (?)")),
                List.of(PublishedWriteClaimApi.Bind.from(Long.MAX_VALUE)), 30);
        when(claims.redeem(any())).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(contract));
        assertThrows(ServiceException.class, () -> api.executeClaim(new PublishedQueryApi.Ticket(TICKET)));
        connection.setConfigurationRevision("published-revision");
        assertThrows(ServiceException.class, () -> api.executeClaim(new PublishedQueryApi.Ticket(TICKET)));
        verifyNoInteractions(runtime);
    }

    @Test void sessionDoesNotReplaceSupplyCertificate() throws Exception {
        request.setSecure(true);
        var api = service(false);
        ServiceException failure = assertThrows(ServiceException.class,
                () -> api.query(query("SELECT 1", List.of())));
        assertEquals(401, failure.getCode());
        verifyNoInteractions(databases, runtime);
    }

    @Test void onlyReadStatementsCanReachDatabase() throws Exception {
        usePostgres();
        var api = service(true);
        assertThrows(ServiceException.class, () -> api.query(query("UPDATE test SET id=0", List.of())));
        verifyNoInteractions(runtime);
    }

    @Test void disabledSourceIsRejectedBeforeCredentialsOrJdbcAreUsed() {
        var mapper = mock(cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper.class);
        var database = new cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO();
        database.setStatus(cn.iocoder.yudao.framework.common.enums.CommonStatusEnum.DISABLE.getStatus());
        when(mapper.selectById(10L)).thenReturn(database);
        var resolver = new AccessDatabaseResolver();
        org.springframework.test.util.ReflectionTestUtils.setField(resolver, "databaseMapper", mapper);
        var failure = assertThrows(ServiceException.class, () -> resolver.resolveEnabled(10L, DATABASE_NOT_EXISTS));
        assertEquals(DATABASE_NOT_EXISTS.getCode(), failure.getCode());
    }

    @Test void approvedColumnsAreEnforcedBeforeAnyDatabaseRead() throws Exception {
        usePostgres();
        var scope = new PublishedQueryApi.Projection("public", "example", List.of("id"));
        var request = new PublishedQueryApi.Query(10L, "SELECT secret FROM public.example", List.of(), 1, 5, 30, scope);
        var failure = assertThrows(ServiceException.class, () -> service(true).query(request));
        assertEquals(cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_PUBLISHED_SCOPE_INVALID.getCode(),
                failure.getCode());
        verifyNoInteractions(runtime);
    }

    @Test void queryBindsTypedValuesAndReturnsExactDecimal() throws Exception {
        usePostgres();
        var metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        var jdbc = new JdbcSelectResult();
        jdbc.setList(List.of(Map.of("amount", new BigDecimal("12345678901234567890.050"))));
        jdbc.setTotal(1L);
        String sql = "SELECT ? AS amount";
        when(metadata.pageDataBySql(null, null, null, sql, List.of(Long.MAX_VALUE), 1, 20, null, 30)).thenReturn(jdbc);
        var result = service(true).query(query(sql, List.of(PublishedQueryApi.Bind.from(Long.MAX_VALUE))));
        assertEquals("12345678901234567890.050", result.getCheckedData().getList().getFirst().get("amount"));
        assertEquals(1L, result.getData().getTotal());
    }
}
