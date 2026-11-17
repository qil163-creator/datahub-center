package cn.iocoder.yudao.module.governance.service.identify;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.governance.dal.dataobject.identify.SafetyScanRunDO;
import cn.iocoder.yudao.module.governance.dal.mysql.identify.SafetyScanRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SafetyScanExecutorTest extends BaseMockitoUnitTest {

    @InjectMocks private SafetyScanExecutor executor;
    @Mock private DatabaseApi databaseApi;
    @Mock private SafetyScanRunMapper runs;

    @BeforeEach
    void startWorkers() {
        executor.init();
    }

    @AfterEach
    void stopWorkers() {
        executor.destroy();
    }

    @Test
    void missingTableMetadataCannotProduceSuccessfulZeroHits() {
        when(databaseApi.getTableColumnList(1L, "a")).thenThrow(new IllegalStateException("远端不可用"));
        when(databaseApi.getTableColumnList(1L, "b")).thenThrow(new IllegalStateException("远端不可用"));

        executor.submit(1L, List.of("a", "b"), List.of());

        SafetyScanRunDO run = terminalUpdate();
        assertThat(run.getRunStatus()).isEqualTo(SafetyScanRunDO.RUN_STATUS_FAILED);
        assertThat(run.getTableCount()).isEqualTo(2);
        assertThat(run.getHitCount()).isZero();
        assertThat(run.getErrorInfo()).contains("2");
    }

    @Test
    void successfulReadWithNoColumnsIsAValidEmptyResult() {
        when(databaseApi.getTableColumnList(1L, "a")).thenReturn(CommonResult.success(List.of()));

        executor.submit(1L, List.of("a"), List.of());

        assertThat(terminalUpdate().getRunStatus()).isEqualTo(SafetyScanRunDO.RUN_STATUS_SUCCESS);
    }

    @Test
    void partialFailureRemainsVisibleAndRetainsTheTableCount() {
        when(databaseApi.getTableColumnList(1L, "a")).thenReturn(CommonResult.success(List.of()));
        when(databaseApi.getTableColumnList(1L, "b")).thenThrow(new IllegalStateException("远端不可用"));

        executor.submit(1L, List.of("a", "b"), List.of());

        SafetyScanRunDO run = terminalUpdate();
        assertThat(run.getRunStatus()).isEqualTo(SafetyScanRunDO.RUN_STATUS_FAILED);
        assertThat(run.getErrorInfo()).contains("2 张表，1 张表字段读取失败");
    }

    @Test
    void busyScansRejectQuicklyAndReleaseTheRejectedDatabase() throws Exception {
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(databaseApi.getTableColumnList(anyLong(), eq("a"))).thenAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return CommonResult.success(List.of());
        });
        try {
            for (long id = 1; id <= 4; id++) {
                executor.submit(id, List.of("a"), List.of());
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            ServiceException rejection = assertThrows(ServiceException.class,
                    () -> executor.submit(5L, List.of("a"), List.of()));

            assertThat(rejection.getCode()).isEqualTo(429);
            assertThat(executor.isRunning(5L)).isFalse();
            assertThat(terminalUpdate().getRunStatus()).isEqualTo(SafetyScanRunDO.RUN_STATUS_FAILED);
        } finally {
            release.countDown();
        }
    }

    private SafetyScanRunDO terminalUpdate() {
        var update = ArgumentCaptor.forClass(SafetyScanRunDO.class);
        verify(runs, timeout(5000)).updateById(update.capture());
        return update.getValue();
    }
}
