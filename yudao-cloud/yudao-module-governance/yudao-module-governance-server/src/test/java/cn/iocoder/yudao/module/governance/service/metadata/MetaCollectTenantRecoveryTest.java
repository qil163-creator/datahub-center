package cn.iocoder.yudao.module.governance.service.metadata;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaCollectTaskDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaCollectTaskMapper;
import cn.iocoder.yudao.module.governance.job.metadata.MetaCollectTaskJob;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaCollectExecutor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaCollectTenantRecoveryTest {
    @Mock MetaCollectTaskMapper taskMapper;
    @Mock MetaCollectExecutor collectExecutor;
    @InjectMocks MetaCollectTaskJob job;
    @AfterEach void clear() { TenantContextHolder.clear(); }
    @Test void globalScanWithoutTenantContextContinuesAfterOneFailure() {
        TenantContextHolder.clear();
        MetaCollectTaskDO first=MetaCollectTaskDO.builder().id(1L).build();
        MetaCollectTaskDO second=MetaCollectTaskDO.builder().id(2L).build();
        when(taskMapper.selectDueList(any())).thenReturn(List.of(first,second));
        when(collectExecutor.submitScheduled(eq(1L),any(),any())).thenAnswer(i -> {
            assertThat(TenantContextHolder.getTenantId()).isNull();
            throw new IllegalStateException("queue full");
        });
        when(collectExecutor.submitScheduled(eq(2L),any(),any())).thenAnswer(i -> {
            assertThat(TenantContextHolder.getTenantId()).isNull();
            assertThat(TenantContextHolder.isIgnore()).isFalse();return true;
        });
        job.execute();
        verify(collectExecutor).submitScheduled(eq(2L),any(),any());
        verify(collectExecutor).recoverAndDispatch();
        assertThat(TenantContextHolder.isIgnore()).isFalse();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }
}
