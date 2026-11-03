package cn.iocoder.yudao.module.dataforge.service.execution;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class RunProtocolTest {
    @ParameterizedTest
    @CsvSource({
            "true, NONE, false, false, true",
            "true, COMPLETE, false, false, true",
            "true, PARTIAL, false, false, false",
            "true, UNKNOWN, false, false, false",
            "false, NONE, false, true, false",
            "false, COMPLETE, false, true, false",
            "false, PARTIAL, false, true, false",
            "false, UNKNOWN, false, true, false",
            "true, COMPLETE, true, true, false",
            "true, PARTIAL, true, true, false",
            "true, PARTIAL, false, true, true",
            "true, UNKNOWN, false, true, true"
    })
    void slotRequiresExitEvidenceQualityCompletionAndOutputReview(boolean terminated, String effect,
                                                                 boolean qualityPending, boolean reviewed,
                                                                 boolean expected) {
        assertEquals(expected, RunProtocol.canRelease(terminated, RunProtocol.Effect.valueOf(effect),
                qualityPending, reviewed));
    }

    @org.junit.jupiter.api.Test
    void missingEffectEvidenceCannotReleaseEvenAfterReview() {
        assertFalse(RunProtocol.canRelease(true, null, false, true));
        assertFalse(RunProtocol.canRelease(true, null, false, false));
    }

    @ParameterizedTest
    @CsvSource({"0,0.0,5", "1,0.0,10", "3,0.5,44", "5,0.0,160", "6,0.0,300",
            "1000,0.999,300"})
    void failureBackoffIsBoundedAndDoesNotOverflow(int failures, double jitter, long expected) {
        assertEquals(expected, RunProtocol.retryDelaySeconds(failures, jitter));
    }

    @org.junit.jupiter.api.Test
    void invalidBackoffInputsFailFast() {
        assertThrows(IllegalArgumentException.class, () -> RunProtocol.retryDelaySeconds(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> RunProtocol.retryDelaySeconds(0, -0.1));
        assertThrows(IllegalArgumentException.class, () -> RunProtocol.retryDelaySeconds(0, 1));
        assertThrows(IllegalArgumentException.class, () -> RunProtocol.retryDelaySeconds(0, Double.NaN));
    }
}
