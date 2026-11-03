package cn.iocoder.yudao.module.asset.job.market;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.job.TenantJob;
import cn.iocoder.yudao.framework.tenant.core.job.TenantJobAspect;
import cn.iocoder.yudao.framework.tenant.core.service.TenantFrameworkService;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.service.market.MarketApplyService;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MarketApplyExpireJobTest extends BaseMockitoUnitTest {

    @InjectMocks
    private MarketApplyExpireJob job;
    @Mock
    private MarketApplyService marketApplyService;

    private Logger logger;
    private Level previousLevel;
    private boolean previousAdditive;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureJobLogs() {
        logger = (Logger) LoggerFactory.getLogger(MarketApplyExpireJob.class);
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void restoreJobLogs() {
        logger.detachAppender(captured);
        captured.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    @Test
    void singleSchedulerIsXxlJobWithoutTenantFanOut() throws Exception {
        Method execute = MarketApplyExpireJob.class.getDeclaredMethod("execute");
        assertTrue(execute.isAnnotationPresent(XxlJob.class));
        assertEquals("marketApplyExpireJob", execute.getAnnotation(XxlJob.class).value());
        assertFalse(execute.isAnnotationPresent(TenantJob.class));
        assertFalse(execute.isAnnotationPresent(Scheduled.class));
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "4, 7"})
    void bothPhasesSucceedInOrder(int expired, int retried) {
        when(marketApplyService.expireDueApplies()).thenReturn(expired);
        when(marketApplyService.retryDeliveries()).thenReturn(retried);

        assertDoesNotThrow(job::execute);

        assertBothPhasesCalledOnceInOrder();
        assertSafeLogs(
                "[marketApplyExpireJob] phase=expireDueApplies count=" + expired,
                "[marketApplyExpireJob] phase=retryDeliveries count=" + retried);
    }

    @Test
    void expiryFailureStillRunsRetryThenReportsSanitizedFailure() {
        when(marketApplyService.expireDueApplies()).thenThrow(sensitiveFailure("expiry"));
        when(marketApplyService.retryDeliveries()).thenReturn(7);

        RuntimeException failure = assertThrows(RuntimeException.class, job::execute);

        assertBothPhasesCalledOnceInOrder();
        assertSanitizedFailure(failure);
        assertSafeLogs(
                "[marketApplyExpireJob] phase=expireDueApplies exception=java.lang.IllegalArgumentException",
                "[marketApplyExpireJob] phase=retryDeliveries count=7");
    }

    @Test
    void retryFailureAfterSuccessfulExpiryReportsSanitizedFailure() {
        when(marketApplyService.expireDueApplies()).thenReturn(4);
        when(marketApplyService.retryDeliveries()).thenThrow(sensitiveFailure("retry"));

        RuntimeException failure = assertThrows(RuntimeException.class, job::execute);

        assertBothPhasesCalledOnceInOrder();
        assertSanitizedFailure(failure);
        assertSafeLogs(
                "[marketApplyExpireJob] phase=expireDueApplies count=4",
                "[marketApplyExpireJob] phase=retryDeliveries exception=java.lang.IllegalArgumentException");
    }

    @Test
    void bothFailuresAreReportedAfterBothPhasesWithoutOriginalExceptionChains() {
        when(marketApplyService.expireDueApplies()).thenThrow(sensitiveFailure("expiry"));
        when(marketApplyService.retryDeliveries()).thenThrow(sensitiveFailure("retry"));

        RuntimeException failure = assertThrows(RuntimeException.class, job::execute);

        assertBothPhasesCalledOnceInOrder();
        assertSanitizedFailure(failure);
        assertSafeLogs(
                "[marketApplyExpireJob] phase=expireDueApplies exception=java.lang.IllegalArgumentException",
                "[marketApplyExpireJob] phase=retryDeliveries exception=java.lang.IllegalArgumentException");
    }

    @Test
    void expiryErrorPropagatesUnchangedWithoutRunningRetry() {
        AssertionError fatal = new AssertionError("test-only-fatal-expiry");
        when(marketApplyService.expireDueApplies()).thenThrow(fatal);

        assertSame(fatal, assertThrows(AssertionError.class, job::execute));

        verify(marketApplyService).expireDueApplies();
        verify(marketApplyService, never()).retryDeliveries();
        verifyNoMoreInteractions(marketApplyService);
        assertSafeLogs();
    }

    @Test
    void retryErrorPropagatesUnchangedAfterSuccessfulExpiry() {
        AssertionError fatal = new AssertionError("test-only-fatal-retry");
        when(marketApplyService.expireDueApplies()).thenReturn(4);
        when(marketApplyService.retryDeliveries()).thenThrow(fatal);

        assertSame(fatal, assertThrows(AssertionError.class, job::execute));

        assertBothPhasesCalledOnceInOrder();
        assertSafeLogs("[marketApplyExpireJob] phase=expireDueApplies count=4");
    }

    @Test
    void retryErrorIsNotReplacedByEarlierExpiryRuntimeFailure() {
        AssertionError fatal = new AssertionError("test-only-fatal-retry");
        when(marketApplyService.expireDueApplies()).thenThrow(sensitiveFailure("expiry"));
        when(marketApplyService.retryDeliveries()).thenThrow(fatal);

        assertSame(fatal, assertThrows(AssertionError.class, job::execute));

        assertBothPhasesCalledOnceInOrder();
        assertSafeLogs(
                "[marketApplyExpireJob] phase=expireDueApplies exception=java.lang.IllegalArgumentException");
    }

    @Test
    void failureReportingDoesNotReadExceptionMessageOrToString() {
        when(marketApplyService.expireDueApplies()).thenThrow(new UnreadableException());
        when(marketApplyService.retryDeliveries()).thenThrow(new UnreadableException());

        RuntimeException failure = assertThrows(RuntimeException.class, job::execute);

        assertBothPhasesCalledOnceInOrder();
        assertSanitizedFailure(failure);
        assertSafeLogs(
                "[marketApplyExpireJob] phase=expireDueApplies exception=" + UnreadableException.class.getName(),
                "[marketApplyExpireJob] phase=retryDeliveries exception=" + UnreadableException.class.getName());
    }

    @Test
    void failedInvocationDoesNotPoisonNextInvocationOnSameJob() {
        when(marketApplyService.expireDueApplies()).thenThrow(sensitiveFailure("expiry")).thenReturn(4);
        when(marketApplyService.retryDeliveries()).thenReturn(7);

        RuntimeException failure = assertThrows(RuntimeException.class, job::execute);
        assertBothPhasesCalledOnceInOrder();
        assertSanitizedFailure(failure);
        clearInvocations(marketApplyService);
        captured.list.clear();

        assertDoesNotThrow(job::execute);

        assertBothPhasesCalledOnceInOrder();
        assertSafeLogs(
                "[marketApplyExpireJob] phase=expireDueApplies count=4",
                "[marketApplyExpireJob] phase=retryDeliveries count=7");
    }

    @Test
    void platformJobRunsOnceWithoutTenantContext() {
        TenantContextHolder.clear();
        when(marketApplyService.expireDueApplies()).thenReturn(4);
        when(marketApplyService.retryDeliveries()).thenReturn(7);
        assertDoesNotThrow(job::execute);
        assertBothPhasesCalledOnceInOrder();
        assertNull(TenantContextHolder.getTenantId());
    }

    private void assertBothPhasesCalledOnceInOrder() {
        var order = inOrder(marketApplyService);
        order.verify(marketApplyService).expireDueApplies();
        order.verify(marketApplyService).retryDeliveries();
        verifyNoMoreInteractions(marketApplyService);
    }

    private static IllegalArgumentException sensitiveFailure(String phase) {
        var failure = new IllegalArgumentException("test-only-sensitive-message-" + phase,
                new IllegalStateException("test-only-sensitive-cause-" + phase));
        failure.addSuppressed(new IllegalStateException("test-only-sensitive-suppressed-" + phase));
        return failure;
    }

    private static void assertSanitizedFailure(RuntimeException failure) {
        assertEquals(IllegalStateException.class, failure.getClass());
        assertEquals("marketApplyExpireJob failed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        var stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));
        assertFalse(stackTrace.toString().contains("test-only-sensitive"));
    }

    private void assertSafeLogs(String... expected) {
        assertEquals(List.of(expected), captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        for (ILoggingEvent event : captured.list) {
            assertNull(event.getThrowableProxy());
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) {
                    assertTrue(argument instanceof String || argument instanceof Integer);
                }
            }
            assertEquals(event.getFormattedMessage().contains(" exception=") ? Level.WARN : Level.INFO,
                    event.getLevel());
        }
    }

    private static final class UnreadableException extends RuntimeException {

        @Override
        public String getMessage() {
            throw new AssertionError("Exception message must not be read");
        }

        @Override
        public String toString() {
            throw new AssertionError("Exception must not be formatted");
        }
    }

}
