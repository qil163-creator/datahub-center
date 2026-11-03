package cn.iocoder.yudao.module.governance.service.statictask;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.statictask.SafetyStaticTaskLogDO;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.statictask.SafetyStaticTaskLogMapper;
import cn.iocoder.yudao.module.governance.job.statictask.SafetyStaticTaskJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实线程复用下验证用户身份传播及失败后的清理。 */
class SafetyStaticTaskContextTest {
    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void reusedWorkerRestoresEachOwnerAndClearsContextEvenAfterFailure() throws Exception {
        var executor = new SafetyStaticTaskExecutor();
        var tasks = mock(SafetyStaticTaskMapper.class);
        var logs = mock(SafetyStaticTaskLogMapper.class);
        var observed = new ArrayList<WorkerContext>();
        when(tasks.selectById(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            if (Thread.currentThread().getName().equals("static-context-test")) {
                observed.add(context());
            }
            return task(id);
        });
        var finishes = new AtomicInteger();
        doAnswer(call -> {
            if (finishes.incrementAndGet() == 1) throw new IllegalStateException("测试日志写入失败");
            return 1;
        }).when(logs).updateById(any(SafetyStaticTaskLogDO.class));
        ReflectionTestUtils.setField(executor, "taskMapper", tasks);
        ReflectionTestUtils.setField(executor, "logMapper", logs);
        try (var worker = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "static-context-test"))) {
            ReflectionTestUtils.setField(executor, "executor", worker);
            worker.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(auth("old-worker"));
            }).get(5, TimeUnit.SECONDS);
            SecurityContextHolder.getContext().setAuthentication(auth("actor-a"));
            assertThat(executor.submit(1L, 1)).isTrue();
            assertCleared(worker.submit(SafetyStaticTaskContextTest::context).get(5, TimeUnit.SECONDS));
            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("actor-a");
            SecurityContextHolder.getContext().setAuthentication(auth("actor-b"));
            assertThat(executor.submit(2L, 1)).isTrue();
            assertCleared(worker.submit(SafetyStaticTaskContextTest::context).get(5, TimeUnit.SECONDS));
            assertThat(observed).containsExactly(new WorkerContext(null, false, "actor-a"),
                    new WorkerContext(null, false, "actor-b"));
            assertThat(executor.isRunning(1L)).isFalse();
            assertThat(executor.isRunning(2L)).isFalse();
            verify(logs, times(2)).insert(any(SafetyStaticTaskLogDO.class));
        }
    }

    @Test
    void submitRejectsMissingTaskBeforeEnqueue() {
        var executor = new SafetyStaticTaskExecutor();
        var tasks = mock(SafetyStaticTaskMapper.class);
        var worker = mock(java.util.concurrent.ExecutorService.class);
        ReflectionTestUtils.setField(executor, "taskMapper", tasks);
        ReflectionTestUtils.setField(executor, "executor", worker);
        assertThatThrownBy(() -> executor.submit(1L, 1)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(worker);
    }

    @Test
    void globalScanWithoutTenantContextContinuesAfterOneFailure() {
        var tasks = mock(SafetyStaticTaskMapper.class);
        var service = mock(SafetyStaticTaskService.class);
        when(tasks.selectTimerOnlineList()).thenAnswer(call -> {
            assertThat(TenantContextHolder.getTenantId()).isNull();
            return List.of(task(1), task(2), task(3));
        });
        var submissions = new ArrayList<WorkerContext>();
        doAnswer(call -> {
            submissions.add(context());
            if (Long.valueOf(1L).equals(call.getArgument(0))) throw new IllegalStateException("测试单项提交失败");
            return null;
        }).when(service).executeTaskBySchedule(anyLong());
        var job = new SafetyStaticTaskJob();
        ReflectionTestUtils.setField(job, "taskMapper", tasks);
        ReflectionTestUtils.setField(job, "taskService", service);
        job.execute();
        assertThat(submissions).containsExactly(new WorkerContext(null, false, null),
                new WorkerContext(null, false, null), new WorkerContext(null, false, null));
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(TenantContextHolder.isIgnore()).isFalse();
        verify(service).executeTaskBySchedule(3L);
    }

    private static SafetyStaticTaskDO task(long id) {
        var task = SafetyStaticTaskDO.builder().id(id).cronExpression("* * * * * *").fieldRulesJson("[]").build();

        return task;
    }
    private static UsernamePasswordAuthenticationToken auth(String principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }
    private static WorkerContext context() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return new WorkerContext(TenantContextHolder.getTenantId(), TenantContextHolder.isIgnore(),
                authentication == null ? null : authentication.getName());
    }
    private static void assertCleared(WorkerContext context) {
        assertThat(context.tenant()).isNull(); assertThat(context.ignored()).isFalse(); assertThat(context.actor()).isNull();
    }
    private record WorkerContext(Long tenant, boolean ignored, String actor) { }
}
