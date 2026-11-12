package cn.iocoder.yudao.module.dataforge.service.lineage;

import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageTransform;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.SqlLineageStatus;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageNodeRefDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineageReportBuilderTest {

    private static final LineageNodeRefDTO A = LineageNodeRefDTO.table(1L, "ods", "a");
    private static final LineageNodeRefDTO B = LineageNodeRefDTO.table(1L, "dw", "b");
    private static final LineageNodeRefDTO C = LineageNodeRefDTO.table(2L, "dw", "c");

    @Test
    void duplicateEdgesAreCollapsedAndHashIsIndependentOfCollectionOrder() {
        var first = builder().tableEdge(A, B, "PARSED", 1).tableEdge(C, B, "PARSED", 2).tableEdge(A, B, "PARSED", 3)
                .columnEdge(A, "id", B, "id", LineageTransform.IDENTITY, false, false, 1).build();
        var second = builder().columnEdge(A, "id", B, "id", LineageTransform.IDENTITY, false, false, 1)
                .tableEdge(C, B, "PARSED", 2).tableEdge(A, B, "PARSED", 1).build();
        assertEquals(2, first.getTableEdges().size());
        assertEquals(first.getContentHash(), second.getContentHash());
        assertTrue(first.getContentHash().matches("[0-9a-f]{64}"));
        assertEquals("DIRECT", first.getColumnEdges().getFirst().getTransformType());
        assertEquals("IDENTITY", first.getColumnEdges().getFirst().getTransformSubtype());
    }

    @Test
    void manualAndParsedEdgesBetweenSameTablesAreKeptSeparately() {
        var report = builder().tableEdge(A, B, "PARSED", null).tableEdge(A, B, "MANUAL", null).build();
        assertEquals(2, report.getTableEdges().size());
        assertNotEquals(builder().tableEdge(A, B, "PARSED", null).build().getContentHash(), report.getContentHash());
    }

    @Test
    void overlongNamesAreDroppedAndDegradeStatus() {
        var report = builder().tableEdge(LineageNodeRefDTO.table(1L, "ods", "x".repeat(300)), B, "PARSED", null)
                .build();
        assertTrue(report.getTableEdges().isEmpty());
        assertEquals("PARTIAL", report.getStatus());
        assertEquals("NAME_TOO_LONG", report.getIssues().getFirst().getCode());
    }

    @Test
    void statusMergeKeepsTheMostInformativeOutcome() {
        assertEquals(SqlLineageStatus.PARTIAL, LineageReportBuilder.merge(SqlLineageStatus.SUCCESS, SqlLineageStatus.PARTIAL));
        assertEquals(SqlLineageStatus.UNSUPPORTED,
                LineageReportBuilder.merge(SqlLineageStatus.SUCCESS, SqlLineageStatus.UNSUPPORTED));
        assertEquals(SqlLineageStatus.UNSUPPORTED,
                LineageReportBuilder.merge(SqlLineageStatus.UNSUPPORTED, SqlLineageStatus.PARTIAL));
        assertEquals(SqlLineageStatus.PARTIAL, LineageReportBuilder.merge(SqlLineageStatus.PARTIAL, SqlLineageStatus.SUCCESS));
    }

    @Test
    void removedAndRejectedReportsCarryNoEdges() {
        var removed = LineageReportBuilder.removed("DEV_TASK", 7L, "t", 3L, 101);
        assertEquals("REMOVED", removed.getStatus());
        assertTrue(removed.getTableEdges().isEmpty());

        var original = builder().tableEdge(A, B, "PARSED", null).build();
        var rejected = LineageReportBuilder.rejected(original, "血缘上报内容无效：节点不合法");
        assertEquals("FAILED", rejected.getStatus());
        assertTrue(rejected.getTableEdges().isEmpty());
        assertEquals(original.getSourceRevision(), rejected.getSourceRevision());
        assertEquals("REPORT_REJECTED", rejected.getIssues().getFirst().getCode());
    }

    private static LineageReportBuilder builder() {
        return new LineageReportBuilder("DEV_TASK", 7L, "ods_to_dw", 3L, 101);
    }
}
