package cn.iocoder.yudao.framework.operatelog.core.service;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.operatelog.config.YudaoOperateLogConfiguration;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import com.mzt.logapi.beans.LogRecord;
import com.mzt.logapi.service.ILogRecordService;
import com.mzt.logapi.context.LogRecordContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class LogRecordServiceImplTest {

    @BeforeEach
    void login() {
        var request = new MockHttpServletRequest("POST", "/admin-api/fixture/create");
        request.addHeader("User-Agent", "audit-fixture");
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(42L).setUserType(2), request);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void run(BiConsumer<Fixture, FixtureConfiguration> check) {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(YudaoOperateLogConfiguration.class))
                .withUserConfiguration(FixtureConfiguration.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    check.accept(context.getBean(Fixture.class), context.getBean(FixtureConfiguration.class));
                });
    }

    @Test
    void existingAnnotationRecordsTheCreatedIdAndDirectActionWithoutBusinessPayload() {
        run((fixture, config) -> {
            fixture.create("测试对象", "fixture-private-payload");
            assertEquals(1, config.records.size());
            var record = config.records.getFirst();
            assertEquals(9007199254740993L, record.getBizId());
            assertEquals(42L, record.getUserId());
            assertEquals(2, record.getUserType());
            assertEquals("创建了对象【测试对象】", record.getAction());
            assertEquals("/admin-api/fixture/create", record.getRequestUrl());
            assertEquals("audit-fixture", record.getUserAgent());
            assertFalse(record.toString().contains("fixture-private-payload"));
        });
    }

    @Test
    void actionNameAndContentDescribeDifferentLevelsOfDetail() {
        run((fixture, config) -> {
            fixture.rename(91L, "原对象", "新对象");
            var record = config.records.getFirst();
            assertEquals("修改对象", record.getSubType());
            assertEquals("修改了对象【原对象】，名称从【原对象】调整为【新对象】", record.getAction());
            assertNotEquals(record.getSubType(), record.getAction());
        });
    }

    @Test
    void deleteUsesTheNameCapturedBeforeTheBusinessObjectIsRemoved() {
        run((fixture, config) -> {
            fixture.delete(92L, "待删除对象");
            var record = config.records.getFirst();
            assertEquals("删除对象", record.getSubType());
            assertEquals("删除了对象【待删除对象】", record.getAction());
        });
    }

    @Test
    void exceptionsFailedResultsAndUnappliedFalseResultsDoNotRecordSuccess() {
        run((fixture, config) -> {
            assertThrows(IllegalStateException.class, () -> fixture.throwing(1L));
            fixture.failure(1L);
            fixture.notApplied(1L);
            assertTrue(config.records.isEmpty());
        });
    }

    @Test
    void explicitlyAcceptedDeferredRequestsKeepTheirActualMeaning() {
        run((fixture, config) -> {
            fixture.deferred(2L);
            assertEquals("提交退役请求", config.records.getFirst().getAction());
        });
    }

    @Test
    void batchExpressionsKeepOnlySuccessfulIdsAndPreserveLargeIntegers() {
        run((fixture, config) -> {
            fixture.batch(List.of(new Item(true, new ObjectId(9007199254740993L)),
                    new Item(false, new ObjectId(2L)), new Item(true, new ObjectId(3L))));
            assertEquals(List.of(9007199254740993L, 3L), config.records.stream()
                    .map(OperateLogCreateReqDTO::getBizId).toList());
        });
    }

    @Test
    void emptySuccessfulBatchDoesNotCreateAFictitiousObjectLog() {
        run((fixture, config) -> {
            fixture.batch(List.of());
            assertTrue(config.records.isEmpty());
        });
    }

    @Test
    void transactionCommitUsesCapturedIdentityAndRollbackDoesNotWriteLogs() {
        run((fixture, config) -> {
            TransactionTemplate transactions = new TransactionTemplate(new FixtureTransactions());
            transactions.execute(status -> {
                fixture.update(51L);
                assertTrue(config.records.isEmpty());
                status.setRollbackOnly();
                return null;
            });
            assertTrue(config.records.isEmpty());
            transactions.execute(status -> {
                fixture.update(52L);
                assertTrue(config.records.isEmpty());
                SecurityContextHolder.clearContext();
                RequestContextHolder.resetRequestAttributes();
                return null;
            });
            assertEquals(52L, config.records.getFirst().getBizId());
            assertEquals(42L, config.records.getFirst().getUserId());
        });
    }

    @Test
    void existingDetailedILogRecordServiceRecordSuppressesTheControllerSummary() {
        run((fixture, config) -> {
            fixture.detailed();
            assertEquals(1, config.records.size());
            assertEquals("删除了对象【测试对象】", config.records.getFirst().getAction());
        });
    }

    @Test
    void originalAnnotationWithoutTheNewMarkerKeepsItsOriginalContract() {
        run((fixture, config) -> {
            fixture.legacy(61L);
            assertEquals(61L, config.records.getFirst().getBizId());
            assertEquals("删除了对象【原有对象】", config.records.getFirst().getAction());
            assertEquals("原有附加信息", config.records.getFirst().getExtra());
        });
    }

    @Test
    void nullableModuleScopeAndAnonymousRequestsFollowTheExistingIdentityBoundary() {
        run((fixture, config) -> {
            fixture.update(null);
            assertEquals(0L, config.records.getFirst().getBizId());
            SecurityContextHolder.clearContext();
            fixture.update(71L);
            assertEquals(1, config.records.size());
        });
    }

    @Test
    void recordingFailureDoesNotChangeTheSuccessfulBusinessResponse() {
        run((fixture, config) -> {
            config.fail = true;
            assertTrue(fixture.update(81L).isSuccess());
            assertTrue(config.records.isEmpty());
        });
    }

    public record ObjectId(Long id) { }
    public record Item(boolean success, ObjectId result) { }

    public static class Fixture {
        private final ILogRecordService recorder;
        Fixture(ILogRecordService recorder) { this.recorder = recorder; }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "新增对象",
                bizNo = "{{#_ret.data}}", success = "创建了对象【{{#name}}】", extra = "BUSINESS_OPERATION",
                successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Long> create(String name, String privatePayload) {
            return CommonResult.success(9007199254740993L);
        }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "修改对象", bizNo = "{{#id}}",
                success = "修改了对象【{{#beforeName}}】，名称从【{{#beforeName}}】调整为【{{#newName}}】",
                extra = "BUSINESS_OPERATION", successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Boolean> rename(Long id, String oldName, String newName) {
            LogRecordContext.putVariable("beforeName", oldName);
            return CommonResult.success(true);
        }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "删除对象", bizNo = "{{#id}}",
                success = "删除了对象【{{#deletedName}}】", extra = "BUSINESS_OPERATION",
                successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Boolean> delete(Long id, String oldName) {
            LogRecordContext.putVariable("deletedName", oldName);
            return CommonResult.success(true);
        }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "修改对象",
                bizNo = "{{(#id) ?: 0}}", success = "修改对象", extra = "BUSINESS_OPERATION",
                successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Boolean> update(Long id) { return CommonResult.success(true); }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "修改对象",
                bizNo = "{{(#id) ?: 0}}", success = "修改对象", extra = "BUSINESS_OPERATION",
                successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Boolean> throwing(Long id) { throw new IllegalStateException("fixture"); }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "修改对象",
                bizNo = "{{(#id) ?: 0}}", success = "修改对象", extra = "BUSINESS_OPERATION",
                successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Boolean> failure(Long id) { return CommonResult.error(400, "fixture"); }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "修改对象",
                bizNo = "{{(#id) ?: 0}}", success = "修改对象", extra = "BUSINESS_OPERATION",
                successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Boolean> notApplied(Long id) { return CommonResult.success(false); }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "提交退役请求",
                bizNo = "{{#id}}", success = "提交退役请求", extra = "BUSINESS_OPERATION",
                successCondition = "{{#_ret.code == 0}}")
        public CommonResult<Boolean> deferred(Long id) { return CommonResult.success(false); }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "批量处理对象",
                bizNo = "{{#_ret.data.?[success].![result.id]}}", success = "批量处理对象", extra = "BUSINESS_OPERATION",
                successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<List<Item>> batch(List<Item> items) { return CommonResult.success(items); }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "测试对象", subType = "删除对象", bizNo = "1",
                success = "删除对象", extra = "BUSINESS_OPERATION",
                successCondition = "{{#_ret.code == 0 && #_ret.data != false}}")
        public CommonResult<Boolean> detailed() {
            recorder.record(LogRecord.builder().type("测试对象").subType("删除对象").bizNo("1")
                    .action("删除了对象【测试对象】").build());
            return CommonResult.success(true);
        }

        @com.mzt.logapi.starter.annotation.LogRecord(type = "原有对象", subType = "删除对象", bizNo = "{{#id}}",
                success = "删除了对象【原有对象】", extra = "原有附加信息")
        public CommonResult<Boolean> legacy(Long id) { return CommonResult.success(true); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class FixtureConfiguration {
        final List<OperateLogCreateReqDTO> records = new ArrayList<>();
        boolean fail;
        @Bean OperateLogCommonApi operateLogApi() {
            return request -> {
                if (fail) { throw new IllegalStateException("fixture"); }
                records.add(request);
                return CommonResult.success(true);
            };
        }
        @Bean Fixture fixture(ILogRecordService recorder) { return new Fixture(recorder); }
    }

    static class FixtureTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
