package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;
import cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommitReceiptContractTest {
    @ParameterizedTest @ValueSource(strings={"0","2","03","-1","18446744073709551616","1;SELECT 1",""})
    void invalidXidCannotEnterReceipt(String xid) {
        assertThrows(IllegalArgumentException.class, () -> receipt(xid));
    }

    @Test void xid8RemainsStringWithJackson2And3AndLegacySummaryDoesNotInventReceipts() throws Exception {
        var j2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var j3 = tools.jackson.databind.json.JsonMapper.builder().build();
        for (String xid : List.of("9007199254740993", "18446744073709551615")) {
            var value = receipt(xid);
            String json2 = j2.writeValueAsString(value), json3 = j3.writeValueAsString(value);
            assertEquals(j2.readTree(json2), j2.readTree(json3));
            assertTrue(j2.readTree(json3).get("transactionId").isTextual());
            assertEquals(value, j2.readValue(json3, TransactionCommitReceipt.class));
            assertEquals(value, j3.readValue(json2, TransactionCommitReceipt.class));
        }
        String old = "{\"operation\":\"FULL_SCAN_TABLE\",\"readRows\":1,\"acknowledgedRows\":1,"
                + "\"sourceColumns\":1,\"copiedColumns\":1,\"substitutedNullValues\":0,"
                + "\"transformed\":false,\"generatedColumns\":false,\"customScripts\":false}";
        assertNull(j2.readValue(old, TableTransferSummary.class).commits());
        assertNull(j3.readValue(old, TableTransferSummary.class).commits());
    }

    @Test void immutableBoundedManifestRecordsMissingAndTruncatedBatches() {
        var collector = new BatchCommitSummary.Collector();
        collector.add(null); collector.add(receipt("100"));
        var first = collector.snapshot();
        for (int i = 0; i < 100; i++) collector.add(receipt(Integer.toString(101 + i)));
        var value = collector.snapshot();
        assertEquals(102, value.totalBatches()); assertEquals(101, value.witnessedBatches());
        assertEquals(16, value.receipts().size()); assertEquals(2, value.receipts().getFirst().batch());
        assertEquals(1, first.receipts().size());
        assertThrows(UnsupportedOperationException.class, () -> value.receipts().clear());
        assertThrows(IllegalArgumentException.class, () -> new BatchCommitSummary(1, 2, List.of()));
    }

    @Test void legacyWriterIsCalledOnceAndNeverInventsReceipt() {
        var writer = mock(IDatabaseWriter.class);
        when(writer.writeWithReceipt(anyList(), anyList())).thenCallRealMethod();
        when(writer.write(anyList(), anyList())).thenReturn(1L);
        var result = writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{1}));
        assertEquals(1, result.acknowledgedRows()); assertNull(result.receipt());
        verify(writer, times(1)).write(anyList(), anyList());
    }

    static TransactionCommitReceipt receipt(String xid) {
        return new TransactionCommitReceipt(1, "POSTGRESQL_XID8_V1", xid, "a".repeat(64));
    }
}
