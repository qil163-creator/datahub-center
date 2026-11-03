package cn.iocoder.yudao.module.dataforge.service.etl;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlOperationsSnapshot;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlOperationsMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlOperationsMetricsTest {
    @Test void scrapeUsesSnapshotAndFailedRefreshRetainsLastKnownCountsWithUnavailableFlag() {
        var mapper = mock(EtlOperationsMapper.class); var registry = new SimpleMeterRegistry();
        var metrics = new EtlOperationsMetrics(mapper, registry);
        assertTrue(Double.isNaN(registry.get("dataforge.etl.delivery.pending").tag("kind","commit").gauge().value()));
        verifyNoInteractions(mapper);
        var row = new EtlOperationsSnapshot(); row.setKind("commit"); row.setPending(2L); row.setFailed(1L); row.setOldestAge(42.0);
        when(mapper.snapshot()).thenReturn(List.of(row)); metrics.refresh();
        for (int i=0;i<5;i++) assertEquals(2, registry.get("dataforge.etl.delivery.pending").tag("kind","commit").gauge().value());
        verify(mapper, times(1)).snapshot();
        when(mapper.snapshot()).thenThrow(new IllegalStateException("connection unavailable")); metrics.refresh();
        assertEquals(0, registry.get("dataforge.etl.delivery.metrics.available").gauge().value());
        assertEquals(2, registry.get("dataforge.etl.delivery.pending").tag("kind","commit").gauge().value());
        assertEquals(0, registry.get("dataforge.etl.delivery.failed").tag("kind","recheck").gauge().value());
        assertTrue(registry.getMeters().stream().allMatch(m -> m.getId().getTags().size() <= 1));
        registry.close();
    }
}
