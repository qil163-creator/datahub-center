package cn.iocoder.yudao.module.governance.service.quality;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QualityTaskExecutorCollectTest {

    @Test
    void shouldCollectUnlessExplicitlyDisabled() {
        assertThat(QualityTaskExecutor.shouldCollectIssueDetail(null)).isTrue();
        assertThat(QualityTaskExecutor.shouldCollectIssueDetail(1)).isTrue();
        assertThat(QualityTaskExecutor.shouldCollectIssueDetail(0)).isFalse();
    }
}
