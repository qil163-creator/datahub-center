package cn.iocoder.yudao.framework.operatelog.core.aop;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.operatelog.config.YudaoOperateLogConfiguration;
import cn.iocoder.yudao.framework.operatelog.core.annotation.BusinessOperateLog;
import cn.iocoder.yudao.framework.operatelog.core.service.LogRecordServiceImpl;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.mzt.logapi.beans.LogRecord;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusinessOperateLogAspectTest {

    private final List<OperateLogCreateReqDTO> records = new ArrayList<>();
    private final OperateLogCommonApi api = request -> {
        records.add(request);
        return CommonResult.success(true);
    };
    private Fixture controller;

    @BeforeEach
    void setup() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/fixture/create");
        request.addHeader("User-Agent", "audit-fixture");
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(42L).setUserType(2), request);
        controller = proxy(api);
    }

    private Fixture proxy(OperateLogCommonApi recorder) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Fixture());
        factory.addAspect(new BusinessOperateLogAspect(recorder));
        return factory.getProxy();
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsTheCreatedIdActorAndRequestWithoutSerializingBusinessData() {
        controller.create("fixture-private-payload");
        assertEquals(1, records.size());
        var record = records.getFirst();
        assertEquals(9007199254740993L, record.getBizId());
        assertEquals(42L, record.getUserId());
        assertEquals(2, record.getUserType());
        assertEquals("测试对象", record.getType());
        assertEquals("新增对象", record.getSubType());
        assertEquals("操作请求成功：新增对象", record.getAction());
        assertEquals("/admin-api/fixture/create", record.getRequestUrl());
        assertEquals("audit-fixture", record.getUserAgent());
        assertFalse(record.toString().contains("fixture-private-payload"));
        assertTrue(record.getExtra().contains("REQUEST_SUCCEEDED"));
    }

    @Test
    void exceptionsAndFailedCommonResultsDoNotCreateSuccessRecords() {
        assertThrows(IllegalStateException.class, () -> controller.throwing(1L));
        controller.failure(1L);
        controller.notApplied(1L);
        assertTrue(records.isEmpty());
    }

    @Test
    void commitsBeforeRecordingAndDoesNotRecordRolledBackOperations() {
        TransactionTemplate transactions = new TransactionTemplate(new FixtureTransactions());
        transactions.execute(status -> {
            controller.update(51L);
            assertTrue(records.isEmpty());
            return null;
        });
        assertEquals(51L, records.getFirst().getBizId());
        records.clear();
        transactions.execute(status -> {
            controller.update(52L);
            status.setRollbackOnly();
            return null;
        });
        assertTrue(records.isEmpty());
    }

    @Test
    void detailedDomainLogSuppressesOnlyTheFallbackForTheSameRequest() {
        LogRecordServiceImpl detailed = new LogRecordServiceImpl();
        ReflectionTestUtils.setField(detailed, "operateLogApi", api);
        controller.detailed(detailed);
        assertEquals(1, records.size());
        assertEquals("详细日志", records.getFirst().getSubType());
        controller.update(53L);
        assertEquals(2, records.size());
    }

    @Test
    void supportsRecordPropertiesSuccessfulBatchProjectionAndLosslessIds() throws Exception {
        var method = Fixture.class.getMethod("batch", List.class);
        List<Item> items = List.of(new Item(true, new ObjectId(9007199254740993L)),
                new Item(false, new ObjectId(2L)), new Item(true, new ObjectId(3L)));
        assertEquals(List.of(9007199254740993L, 3L), BusinessOperateLogAspect.resolveIds(
                "#result.?[success].![result.id]", method, new Object[]{items}, items));
        assertEquals(List.of(), BusinessOperateLogAspect.resolveIds(
                "#result.?[success].![result.id]", method, new Object[]{List.of()}, List.of()));
        controller.batch(items);
        assertEquals(List.of(9007199254740993L, 3L), records.stream().map(OperateLogCreateReqDTO::getBizId).toList());
        assertThrows(RuntimeException.class, () -> BusinessOperateLogAspect.resolveIds(
                "T(java.lang.Runtime).getRuntime()", method, new Object[]{items}, items));
    }

    @Test
    void emptySuccessBatchAndAnonymousRequestsAreNotMisreported() {
        controller.batch(List.of());
        SecurityContextHolder.clearContext();
        controller.update(1L);
        assertTrue(records.isEmpty());
    }

    @Test
    void logDeliveryFailurePreservesTheCommittedBusinessResponse() {
        Fixture failingRecorder = proxy(request -> { throw new IllegalStateException("fixture"); });
        assertTrue(failingRecorder.update(61L).isSuccess());
    }

    @Test
    void explicitlyAcceptedDeferredOperationsAreRecordedAsRequests() {
        controller.deferred(62L);
        assertEquals(1, records.size());
        assertEquals("提交退役请求", records.getFirst().getSubType());
    }

    @Test
    void autoConfigurationRegistersAnActiveAspectWithTheExistingRecorder() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoOperateLogConfiguration.class))
                .withUserConfiguration(FixtureConfiguration.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(BusinessOperateLogAspect.class));
                    context.getBean(Fixture.class).update(71L);
                    assertEquals(1, context.getBean(FixtureConfiguration.class).records.size());
                });
    }

    public record ObjectId(Long id) { }
    public record Item(boolean success, ObjectId result) { }

    public static class Fixture {
        @BusinessOperateLog(type = "测试对象", bizId = "#result")
        @Operation(summary = "新增对象")
        public CommonResult<Long> create(String privatePayload) {
            return CommonResult.success(9007199254740993L);
        }

        @BusinessOperateLog(type = "测试对象", bizId = "#id")
        @Operation(summary = "修改对象")
        public CommonResult<Boolean> update(Long id) { return CommonResult.success(true); }

        @BusinessOperateLog(type = "测试对象", bizId = "#id")
        public CommonResult<Boolean> throwing(Long id) { throw new IllegalStateException("fixture"); }

        @BusinessOperateLog(type = "测试对象", bizId = "#id")
        public CommonResult<Boolean> failure(Long id) { return CommonResult.error(400, "fixture"); }

        @BusinessOperateLog(type = "测试对象", bizId = "#id")
        public CommonResult<Boolean> notApplied(Long id) { return CommonResult.success(false); }

        @BusinessOperateLog(type = "测试对象", bizId = "#id", recordOnFalse = true, action = "提交退役请求")
        public CommonResult<Boolean> deferred(Long id) { return CommonResult.success(false); }

        @BusinessOperateLog(type = "测试对象", bizId = "#result.?[success].![result.id]")
        @Operation(summary = "批量处理对象")
        public CommonResult<List<Item>> batch(List<Item> items) { return CommonResult.success(items); }

        @BusinessOperateLog(type = "测试对象", bizId = "'1'")
        public CommonResult<Boolean> detailed(LogRecordServiceImpl recorder) {
            recorder.record(LogRecord.builder().type("测试对象").subType("详细日志").bizNo("1")
                    .action("详细日志").build());
            return CommonResult.success(true);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class FixtureConfiguration {
        final List<OperateLogCreateReqDTO> records = new ArrayList<>();
        @Bean OperateLogCommonApi operateLogApi() {
            return request -> { records.add(request); return CommonResult.success(true); };
        }
        @Bean Fixture fixture() { return new Fixture(); }
    }

    static class FixtureTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
