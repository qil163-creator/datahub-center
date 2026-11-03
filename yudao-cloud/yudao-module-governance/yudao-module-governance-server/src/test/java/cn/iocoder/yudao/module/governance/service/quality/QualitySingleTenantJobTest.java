package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.job.TenantJob;
import cn.iocoder.yudao.module.governance.job.quality.QualityRunRecoveryJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QualitySingleTenantJobTest {
    @AfterEach
    void clear() { TenantContextHolder.clear(); }

    @Test
    void recoveryExecutesExactlyOnceWithoutTenantContext() throws Exception {
        TenantContextHolder.clear();
        var service = mock(QualityRunService.class);
        var job = new QualityRunRecoveryJob();
        ReflectionTestUtils.setField(job, "service", service);

        job.execute();

        verify(service, times(1)).recover();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(QualityRunRecoveryJob.class.getMethod("execute").getAnnotation(TenantJob.class)).isNull();
    }
}
