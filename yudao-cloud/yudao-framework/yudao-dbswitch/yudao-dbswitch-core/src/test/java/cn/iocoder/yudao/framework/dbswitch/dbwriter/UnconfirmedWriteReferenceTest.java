package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnconfirmedWriteReferenceTest {

    @Test
    void existingNoReferenceContractRemainsUnknownAndCauseFree() {
        var failure = new UnconfirmedWriteException();
        assertNull(failure.requestLabel());
        assertEquals(UnconfirmedWriteException.ReceiptObservation.NO_RECEIPT, failure.receiptObservation());
        assertNull(failure.getCause());
    }

    @Test
    void boundedReconciliationReferenceNeverEntersExceptionTextOrAllowsRawCause() {
        String label = "dbswitch_" + "a".repeat(32);
        for (var observation : UnconfirmedWriteException.ReceiptObservation.values()) {
            var failure = new UnconfirmedWriteException(label, observation);
            assertEquals(label, failure.requestLabel());
            assertEquals(observation, failure.receiptObservation());
            assertFalse(failure.toString().contains(label));
            assertEquals(new UnconfirmedWriteException().getMessage(), failure.getMessage());
            assertThrows(IllegalStateException.class, () -> failure.initCause(new RuntimeException("private response")));
            failure.addSuppressed(new RuntimeException("private close response"));
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    @Test
    void unboundedOrExternalContentCannotBecomeAReconciliationLabel() {
        for (var label : List.of("", "a".repeat(129), "label\r\nAuthorization: token", "https://host/path", "{\"body\":1}", "标签")) {
            assertThrows(IllegalArgumentException.class, () -> new UnconfirmedWriteException(label,
                    UnconfirmedWriteException.ReceiptObservation.NO_RECEIPT));
        }
        assertThrows(IllegalArgumentException.class, () -> new UnconfirmedWriteException("valid", null));
        assertThrows(IllegalArgumentException.class, () -> new UnconfirmedWriteException(null,
                UnconfirmedWriteException.ReceiptObservation.COMMITTED_NOT_VISIBLE));
    }
}
