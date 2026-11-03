package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;
import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitRangeReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityCommitRangeTest {
    @ParameterizedTest @ValueSource(strings={"NOT_CHECKED","PASS","MATCH","MISMATCH","UNPROVEN","ROUTE_MISMATCH"})
    void actualRelationInspectionCannotFallBackToTransactionOnlyOrForgeMissingBinding(String status) throws Exception {
        var snapshot = snapshot("RANGE_VISIBLE");
        var evidence = snapshot.evidence();
        when(snapshot.inspectCommitRanges(anyList(),eq("public.orders"))).thenAnswer(call -> {
            List<CommitRangeReceipt> ranges = call.getArgument(0);
            return new ReadSnapshot.RangeVisibility(1,evidence,
                    ranges.stream().map(r -> new ReadSnapshot.RangeObservation(r,"RANGE_VISIBLE",status)).toList());
        });
        var result = QualitySyncEvidenceCollector.inspectCommitRanges(
                observation(List.of(batch(1,commits(100),8))),snapshot,"public.orders");
        if (!"UNPROVEN".equals(status)) {
            assertEquals("COMMIT_RANGE_RESPONSE_INVALID",result.reason()); assertNull(result.visibility());
        } else assertEquals("OBSERVED_NOT_ARRIVAL_PROOF",result.reason());
        verify(snapshot,never()).inspectCommitRanges(anyList());
    }
    static SyncCommitReceiptsDTO commits(long start) {
        var collector = new BatchCommitSummary.Collector();
        for (int i = 0; i < 18; i++) collector.add(1,new TransactionCommitReceipt(1,"POSTGRESQL_XID8_V1",Long.toString(start+i),"a".repeat(64)));
        var summary = collector.snapshot(); var c = summary.coverage();
        return new SyncCommitReceiptsDTO(1,18L,18L,true,summary.receipts().stream().map(e ->
                new SyncCommitReceiptsDTO.Entry(e.batch(),1,e.receipt().protocol(),e.receipt().transactionId(),e.receipt().routeFingerprint())).toList(),
                new SyncCommitReceiptsDTO.Coverage(1,c.protocol(),c.batchCount(),c.acknowledgedRows(),
                        c.firstTransactionId(),c.lastTransactionId(),c.routeFingerprint(),c.digest()));
    }
    static SyncBatchObservationDTO.Batch batch(long run, SyncCommitReceiptsDTO commits, long position) {
        var transfer = new SyncTableTransferDTO(1,"FULL_SCAN_TABLE",18L,18L,1,1,0L,false,false,false,commits);
        return new SyncBatchObservationDTO.Batch(run,1,3L,3,false,false,false,"COMPLETE","rev","TABLE_COPY",
                "CONFIRMED",true,true,18L,position,transfer);
    }
    static QualitySyncEvidenceCollector.Observation observation(List<SyncBatchObservationDTO.Batch> batches) {
        return new QualitySyncEvidenceCollector.Observation("OBSERVED",99L,
                new SyncBatchObservationDTO(1,20L,"rev","POSTGRESQL","public","orders","public.orders",false,false,batches),
                new SyncBoundaryDTO(1,"fixture",20L,"rev","POSTGRESQL","public","orders",5L));
    }
    static ReadSnapshot snapshot(String status) throws Exception {
        var snapshot = mock(ReadSnapshot.class);
        var evidence = new ReadSnapshot.Evidence(1,"POSTGRESQL_REPEATABLE_READ_V1","c".repeat(64));
        when(snapshot.evidence()).thenReturn(evidence);
        when(snapshot.inspectCommitRanges(anyList())).thenAnswer(call -> {
            List<CommitRangeReceipt> ranges = call.getArgument(0);
            return new ReadSnapshot.RangeVisibility(1,evidence,
                    ranges.stream().map(r -> new ReadSnapshot.RangeObservation(r,status)).toList());
        });
        return snapshot;
    }

    @ParameterizedTest @ValueSource(strings={"RANGE_VISIBLE","RANGE_UNPROVEN","NOT_COMMITTED","ROUTE_MISMATCH","STATUS_UNAVAILABLE"})
    void moreThanSixteenBatchesBindAllCoverageWithoutChangingAdmission(String status) throws Exception {
        var before = observation(List.of(batch(9007199254740993L,commits(100),8)));
        var result = QualitySyncEvidenceCollector.inspectCommitRanges(before,snapshot(status));
        assertEquals("OBSERVED_NOT_ARRIVAL_PROOF",result.reason());
        assertEquals(new QualitySyncEvidenceCollector.RangeBinding(9007199254740993L,1,3L),result.bindings().getFirst());
        assertEquals(18,result.visibility().observations().getFirst().receipt().batchCount());
        assertEquals(status,result.visibility().observations().getFirst().status());
        var read = new QualityReadEvidence(); read.setReadMode("UNPROVEN_REPLICA"); read.setCommitRangeScope(result);
        var j2 = new com.fasterxml.jackson.databind.ObjectMapper(); var j3 = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(result,j2.readValue(j3.writeValueAsString(read),QualityReadEvidence.class).getCommitRangeScope());
        assertEquals("UNPROVEN_REPLICA",j3.readValue(j2.writeValueAsString(read),QualityReadEvidence.class).getReadMode());
    }
    @ParameterizedTest @ValueSource(strings={"missing","version","truncated","counter","row-count","sample","first","last","route","digest","null-field"})
    void incompleteOrContradictoryCoverageCannotQueryDatabase(String fault) {
        var original = commits(100); var c = original.coverage();
        var coverage = fault.equals("missing") ? null : new SyncCommitReceiptsDTO.Coverage(
                fault.equals("version") ? 2 : 1,c.protocol(),fault.equals("null-field") ? null : c.batchCount(),
                fault.equals("row-count") ? 19L : 18L,fault.equals("first") ? "99" : c.firstTransactionId(),
                fault.equals("last") ? "115" : c.lastTransactionId(),fault.equals("route") ? "f".repeat(64) : c.routeFingerprint(),
                fault.equals("digest") ? "invalid" : c.digest());
        var value = new SyncCommitReceiptsDTO(1,18L,fault.equals("counter") ? 17L : 18L,!fault.equals("truncated"),
                fault.equals("sample") ? original.receipts().subList(0,15) : original.receipts(),coverage);
        var snapshot = mock(ReadSnapshot.class);
        var result = QualitySyncEvidenceCollector.inspectCommitRanges(observation(List.of(batch(1,value,8))),snapshot);
        assertNotEquals("OBSERVED_NOT_ARRIVAL_PROOF",result.reason()); assertNull(result.visibility());
        verifyNoInteractions(snapshot);
    }
    @ParameterizedTest @ValueSource(strings={"unsupported","sql","snapshot","status","empty"})
    void unavailableOrMalformedResponseIsNotPublished(String fault) throws Exception {
        var snapshot = snapshot(fault.equals("status") ? "PASS" : "RANGE_VISIBLE");
        if (fault.equals("unsupported")) when(snapshot.inspectCommitRanges(anyList())).thenThrow(new java.sql.SQLFeatureNotSupportedException("private"));
        if (fault.equals("sql")) when(snapshot.inspectCommitRanges(anyList())).thenThrow(new java.sql.SQLException("private"));
        if (fault.equals("snapshot")) when(snapshot.evidence()).thenReturn(new ReadSnapshot.Evidence(1,"wrong","d".repeat(64)));
        if (fault.equals("empty")) {
            var evidence = snapshot.evidence();
            when(snapshot.inspectCommitRanges(anyList())).thenReturn(new ReadSnapshot.RangeVisibility(1,evidence,List.of()));
        }
        var result = QualitySyncEvidenceCollector.inspectCommitRanges(observation(List.of(batch(1,commits(100),8))),snapshot);
        assertNull(result.visibility()); assertTrue(result.bindings().isEmpty()); assertFalse(result.toString().contains("private"));
    }
    @Test void onlyPostBoundaryRunsAreCheckedAndTwentyRunWindowIsBounded() throws Exception {
        var batches = new ArrayList<SyncBatchObservationDTO.Batch>();
        for (int i = 0; i < 20; i++) batches.add(batch(i+1,commits(100+i*100),8+i));
        var result = QualitySyncEvidenceCollector.inspectCommitRanges(observation(batches),snapshot("RANGE_VISIBLE"));
        assertEquals(20,result.bindings().size()); assertEquals(20,result.visibility().observations().size());
        batches.add(batch(21,commits(2200),30));
        var snapshot = mock(ReadSnapshot.class);
        assertEquals("COMMIT_RANGE_LIMIT",QualitySyncEvidenceCollector.inspectCommitRanges(observation(batches),snapshot).reason());
        verifyNoInteractions(snapshot);
        var currentOnly = QualitySyncEvidenceCollector.inspectCommitRanges(
                observation(List.of(batch(1,null,4),batch(2,commits(100),8))),snapshot("RANGE_VISIBLE"));
        assertEquals(List.of(new QualitySyncEvidenceCollector.RangeBinding(2L,1,3L)),currentOnly.bindings());
    }
    @Test void legacyWireAndMissingSnapshotDoNotInventCoverage() throws Exception {
        var j2 = new com.fasterxml.jackson.databind.ObjectMapper(); var j3 = tools.jackson.databind.json.JsonMapper.builder().build();
        String legacy = "{\"schemaVersion\":1,\"totalBatches\":0,\"witnessedBatches\":0,\"truncated\":false,\"receipts\":[]}";
        assertNull(j2.readValue(legacy,SyncCommitReceiptsDTO.class).coverage()); assertNull(j3.readValue(legacy,SyncCommitReceiptsDTO.class).coverage());
        var before = observation(List.of(batch(1,commits(100),8)));
        assertEquals("SNAPSHOT_UNAVAILABLE",QualitySyncEvidenceCollector.inspectCommitRanges(before,null).reason());
        assertNull(QualitySyncEvidenceCollector.inspectCommitRanges(new QualitySyncEvidenceCollector.Observation("SOURCE_DIRECT",null,null),null));
    }
}
