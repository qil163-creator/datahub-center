package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitRangeReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommitRangeContractTest {
    @ParameterizedTest @ValueSource(strings={"change","missing","appears"})
    void relationGapBeyondSamplesInvalidatesWholeRange(String mode) {
        var collector = new BatchCommitSummary.Collector();
        String initial = mode.equals("appears") ? null : "c".repeat(64);
        for (int i=0;i<18;i++) collector.add(1, new TransactionCommitReceipt(1,"POSTGRESQL_XID8_V1",
                Integer.toString(100+i),"a".repeat(64),i<17 ? initial : mode.equals("missing") ? null : "d".repeat(64)));
        assertEquals(16,collector.snapshot().receipts().size());
        assertNull(collector.snapshot().coverage());
    }

    @Test void objectBindingRoundtripsAndIsPartOfChainAndSampleValidation() throws Exception {
        var bound = new BatchCommitSummary.Collector(); var other = new BatchCommitSummary.Collector();
        bound.add(1,new TransactionCommitReceipt(1,"POSTGRESQL_XID8_V1","100","a".repeat(64),"c".repeat(64)));
        other.add(1,new TransactionCommitReceipt(1,"POSTGRESQL_XID8_V1","100","a".repeat(64),"d".repeat(64)));
        assertNotEquals(bound.snapshot().coverage().digest(),other.snapshot().coverage().digest());
        var j2 = new com.fasterxml.jackson.databind.ObjectMapper(); var j3 = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(bound.snapshot(),j2.readValue(j3.writeValueAsString(bound.snapshot()),BatchCommitSummary.class));
        assertEquals(bound.snapshot(),j3.readValue(j2.writeValueAsString(bound.snapshot()),BatchCommitSummary.class));
        assertThrows(IllegalArgumentException.class, () -> new BatchCommitSummary(1,1,bound.snapshot().receipts(),other.snapshot().coverage()));
        assertThrows(IllegalArgumentException.class, () -> new TransactionCommitReceipt(1,"POSTGRESQL_XID8_V1","100","a".repeat(64),"bad"));
        assertNull(j2.readValue("{\"schemaVersion\":1,\"protocol\":\"POSTGRESQL_XID8_V1\",\"transactionId\":\"100\",\"routeFingerprint\":\""+"a".repeat(64)+"\"}",
                TransactionCommitReceipt.class).relationFingerprint());
    }
    @Test void allHundredThousandBatchesAreBoundWithoutGrowingSampleOrMutatingPublishedSnapshot() throws Exception {
        var collector = new BatchCommitSummary.Collector();
        collector.add(2, receipt("100"));
        var first = collector.snapshot();
        for (int i = 1; i < 100_000; i++) collector.add(2, receipt(Integer.toString(100 + i)));
        var summary = collector.snapshot();
        assertEquals(16, summary.receipts().size());
        assertEquals(100_000, summary.coverage().batchCount()); assertEquals(200_000, summary.coverage().acknowledgedRows());
        assertEquals("100", summary.coverage().firstTransactionId()); assertEquals("100099", summary.coverage().lastTransactionId());
        assertEquals(1, first.coverage().batchCount()); assertEquals(1, first.receipts().size());
        assertNotEquals(first.coverage().digest(), summary.coverage().digest());
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(summary).length() < 6000);
    }

    @ParameterizedTest @ValueSource(strings={"missing","route","duplicate","backwards","legacy","zero","overflow"})
    void anyGapPermanentlyPreventsFullCoverage(String fault) {
        var collector = new BatchCommitSummary.Collector();
        collector.add(fault.equals("overflow") ? Long.MAX_VALUE : 1, receipt("100"));
        switch (fault) {
            case "missing" -> collector.add(1, null);
            case "route" -> collector.add(1, new TransactionCommitReceipt(1,"POSTGRESQL_XID8_V1","101","b".repeat(64)));
            case "duplicate" -> collector.add(1, receipt("100"));
            case "backwards" -> collector.add(1, receipt("99"));
            case "legacy" -> collector.add(receipt("101"));
            case "zero" -> collector.add(0, receipt("101"));
            default -> collector.add(1, receipt("101"));
        }
        collector.add(1, receipt("102"));
        assertNull(collector.snapshot().coverage());
        assertEquals(3, collector.snapshot().totalBatches());
    }

    @Test void chainBindsEveryBatchRowCountIncludingBeyondSample() {
        var a = new BatchCommitSummary.Collector(); var b = new BatchCommitSummary.Collector();
        for (int i = 0; i < 18; i++) {
            a.add(i == 16 ? 2 : 1, receipt(Integer.toString(100+i)));
            b.add(i == 17 ? 2 : 1, receipt(Integer.toString(100+i)));
        }
        assertEquals(a.snapshot().receipts(), b.snapshot().receipts());
        assertEquals(a.snapshot().coverage().acknowledgedRows(), b.snapshot().coverage().acknowledgedRows());
        assertNotEquals(a.snapshot().coverage().digest(), b.snapshot().coverage().digest());
    }

    @Test void jacksonTwoAndThreeRoundtripUnsignedBoundsAndLegacyJson() throws Exception {
        var collector = new BatchCommitSummary.Collector();
        collector.add(1, receipt("9007199254740993")); collector.add(2, receipt("18446744073709551615"));
        var value = collector.snapshot();
        var j2 = new com.fasterxml.jackson.databind.ObjectMapper(); var j3 = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(value, j2.readValue(j3.writeValueAsString(value), BatchCommitSummary.class));
        assertEquals(value, j3.readValue(j2.writeValueAsString(value), BatchCommitSummary.class));
        assertTrue(j2.valueToTree(value).path("coverage").path("lastTransactionId").isTextual());
        String legacy = "{\"totalBatches\":0,\"witnessedBatches\":0,\"receipts\":[]}";
        assertNull(j2.readValue(legacy, BatchCommitSummary.class).coverage());
        assertNull(j3.readValue(legacy, BatchCommitSummary.class).coverage());
    }

    @Test void malformedRangesCannotAcquireValidCoverage() {
        assertThrows(IllegalArgumentException.class, () -> range(2, 2, "100", "100"));
        assertThrows(IllegalArgumentException.class, () -> range(1, 1, "100", "101"));
        assertThrows(IllegalArgumentException.class, () -> range(2, 1, "100", "101"));
        assertThrows(IllegalArgumentException.class, () -> range(2, 2, "101", "100"));
        assertThrows(IllegalArgumentException.class, () -> range(1, 1, "03", "03"));
        assertThrows(IllegalArgumentException.class, () -> new BatchCommitSummary(1,1,
                List.of(new BatchCommitSummary.Entry(1,receipt("101"))),range(1,1,"100","100")));
        assertThrows(IllegalArgumentException.class, () -> new BatchCommitSummary(2,1,
                List.of(new BatchCommitSummary.Entry(1,receipt("100"))),range(2,2,"100","101")));
        assertNull(new BatchCommitSummary.Collector().snapshot().coverage());
    }
    @Test void legacySnapshotDefaultDoesNotInventRangeSupport() {
        var snapshot = org.mockito.Mockito.mock(ReadSnapshot.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> snapshot.inspectCommitRanges(List.of(range(1,1,"100","100"))));
        assertThrows(java.sql.SQLFeatureNotSupportedException.class,
                () -> snapshot.inspectCommitRanges(List.of(range(1,1,"100","100")), "public.rows"));
    }
    static CommitRangeReceipt range(long count, long rows, String first, String last) {
        return new CommitRangeReceipt(1,"POSTGRESQL_XID8_RANGE_V1",count,rows,first,last,"a".repeat(64),"b".repeat(64));
    }
    static TransactionCommitReceipt receipt(String xid) {
        return new TransactionCommitReceipt(1,"POSTGRESQL_XID8_V1",xid,"a".repeat(64));
    }
}
