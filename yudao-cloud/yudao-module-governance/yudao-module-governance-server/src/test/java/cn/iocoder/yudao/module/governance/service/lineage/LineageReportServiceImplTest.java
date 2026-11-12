package cn.iocoder.yudao.module.governance.service.lineage;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportReqDTO;
import cn.iocoder.yudao.module.governance.api.lineage.dto.LineageReportRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageColumnEdgeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageNodeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageSourceDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.lineage.LineageTableEdgeDO;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageColumnEdgeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageNodeMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageSourceMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.lineage.LineageTableEdgeMapper;
import cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.governance.service.lineage.LineageTestReports.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用内存状态模拟四张表，验证整体替换语义；真实锁、ON CONFLICT 与物理删除由 PostgreSQL 用例覆盖。
 */
class LineageReportServiceImplTest {

    private LineageSourceDO stored;
    private final Map<String, LineageNodeDO> nodes = new LinkedHashMap<>();
    private final List<LineageTableEdgeDO> tableEdges = new ArrayList<>();
    private final List<LineageColumnEdgeDO> columnEdges = new ArrayList<>();
    private final List<List<String>> nodeInsertBatches = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private LineageReportServiceImpl service;
    private LineageTableEdgeMapper tableEdgeMapper;

    @BeforeEach
    void setUp() {
        var sourceMapper = mock(LineageSourceMapper.class);
        var nodeMapper = mock(LineageNodeMapper.class);
        tableEdgeMapper = mock(LineageTableEdgeMapper.class);
        var columnEdgeMapper = mock(LineageColumnEdgeMapper.class);
        when(sourceMapper.insertIfAbsent(anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    if (stored != null) {
                        return 0;
                    }
                    stored = new LineageSourceDO();
                    stored.setId(1L);
                    stored.setSourceType(call.getArgument(0));
                    stored.setSourceId(call.getArgument(1));
                    stored.setSourceName(call.getArgument(2));
                    stored.setStatus(call.getArgument(3));
                    stored.setSourceRevision(LineageSourceDO.UNSET_REVISION);
                    stored.setAnalyzerVersion(0);
                    stored.setContentHash("");
                    return 1;
                });
        when(sourceMapper.selectByKeyForUpdate(anyString(), anyLong()))
                .thenAnswer(call -> BeanUtils.toBean(stored, LineageSourceDO.class));
        when(sourceMapper.updateById(any(LineageSourceDO.class))).thenAnswer(call -> {
            LineageSourceDO update = call.getArgument(0);
            if (update.getSourceName() != null) stored.setSourceName(update.getSourceName());
            if (update.getStatus() != null) stored.setStatus(update.getStatus());
            if (update.getIssues() != null) stored.setIssues(update.getIssues());
            if (update.getTableEdgeCount() != null) stored.setTableEdgeCount(update.getTableEdgeCount());
            if (update.getColumnEdgeCount() != null) stored.setColumnEdgeCount(update.getColumnEdgeCount());
            if (update.getSourceRevision() != null) stored.setSourceRevision(update.getSourceRevision());
            if (update.getAnalyzerVersion() != null) stored.setAnalyzerVersion(update.getAnalyzerVersion());
            if (update.getContentHash() != null) stored.setContentHash(update.getContentHash());
            return 1;
        });
        when(nodeMapper.insertIgnoreExisting(anyList(), anyString())).thenAnswer(call -> {
            List<LineageNodeDO> batch = call.getArgument(0);
            nodeInsertBatches.add(batch.stream().map(LineageNodeDO::getNodeKey).toList());
            int inserted = 0;
            for (LineageNodeDO node : batch) {
                if (!nodes.containsKey(node.getNodeKey())) {
                    node.setId(ids.incrementAndGet());
                    nodes.put(node.getNodeKey(), node);
                    inserted++;
                }
            }
            return inserted;
        });
        when(nodeMapper.selectListByNodeKeys(anyCollection())).thenAnswer(call -> {
            Collection<String> keys = call.getArgument(0);
            return keys.stream().map(nodes::get).filter(java.util.Objects::nonNull).toList();
        });
        when(nodeMapper.fillMissingDisplayNames(anyList(), anyString())).thenAnswer(call -> {
            List<LineageNodeDO> fills = call.getArgument(0);
            fills.forEach(fill -> nodes.values().stream().filter(node -> node.getId().equals(fill.getId())
                    && node.getDisplayName() == null).forEach(node -> node.setDisplayName(fill.getDisplayName())));
            return fills.size();
        });
        when(tableEdgeMapper.deleteBySourceRefId(anyLong())).thenAnswer(call -> {
            int size = tableEdges.size();
            tableEdges.clear();
            return size;
        });
        when(columnEdgeMapper.deleteBySourceRefId(anyLong())).thenAnswer(call -> {
            int size = columnEdges.size();
            columnEdges.clear();
            return size;
        });
        when(tableEdgeMapper.insertEdges(anyList(), anyString())).thenAnswer(call -> {
            List<LineageTableEdgeDO> batch = call.getArgument(0);
            tableEdges.addAll(batch);
            return batch.size();
        });
        when(columnEdgeMapper.insertEdges(anyList(), anyString())).thenAnswer(call -> {
            List<LineageColumnEdgeDO> batch = call.getArgument(0);
            columnEdges.addAll(batch);
            return batch.size();
        });
        service = new LineageReportServiceImpl();
        ReflectionTestUtils.setField(service, "sourceMapper", sourceMapper);
        ReflectionTestUtils.setField(service, "nodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "tableEdgeMapper", tableEdgeMapper);
        ReflectionTestUtils.setField(service, "columnEdgeMapper", columnEdgeMapper);
    }

    @Test
    void firstReportIsAcceptedAndStoresEdgesWithResolvedNodeIds() {
        LineageReportRespDTO resp = service.report(simpleReport(10, 3, HASH_A));

        assertThat(resp.getResult()).isEqualTo("ACCEPTED");
        assertThat(resp.getStoredRevision()).isEqualTo(3L);
        assertThat(resp.getStoredAnalyzerVersion()).isEqualTo(1);
        assertThat(nodes).containsOnlyKeys("TABLE|1|ods|orders", "TABLE|1|dwd|orders");
        assertThat(nodeInsertBatches).singleElement().satisfies(batch -> assertThat(batch).isSorted());
        assertThat(tableEdges).singleElement().satisfies(edge -> {
            assertThat(edge.getSourceRefId()).isEqualTo(1L);
            assertThat(edge.getUpstreamNodeId()).isEqualTo(nodes.get("TABLE|1|ods|orders").getId());
            assertThat(edge.getDownstreamNodeId()).isEqualTo(nodes.get("TABLE|1|dwd|orders").getId());
        });
        assertThat(columnEdges).hasSize(2);
        assertThat(stored.getStatus()).isEqualTo("SUCCESS");
        assertThat(stored.getTableEdgeCount()).isEqualTo(1);
        assertThat(stored.getColumnEdgeCount()).isEqualTo(2);
        assertThat(stored.getContentHash()).isEqualTo(HASH_A);
        assertThat(stored.getIssues()).contains("UNSUPPORTED_STATEMENT");
    }

    @Test
    void sameRevisionAndHashIsNoopWithoutTouchingEdges() {
        service.report(simpleReport(10, 3, HASH_A));
        clearInvocations(tableEdgeMapper);

        LineageReportRespDTO resp = service.report(simpleReport(10, 3, HASH_A));

        assertThat(resp.getResult()).isEqualTo("NOOP");
        assertThat(resp.getStoredRevision()).isEqualTo(3L);
        verify(tableEdgeMapper, never()).deleteBySourceRefId(anyLong());
        assertThat(tableEdges).hasSize(1);
    }

    @Test
    void sameRevisionWithDifferentHashReplacesEdges() {
        service.report(simpleReport(10, 3, HASH_A));
        LineageReportReqDTO changed = report(10, 3, 1, HASH_B);
        changed.getTableEdges().add(tableEdge(table(1, "dwd", "orders"), table(1, "ads", "sales")));

        assertThat(service.report(changed).getResult()).isEqualTo("ACCEPTED");
        assertThat(tableEdges).singleElement().satisfies(edge ->
                assertThat(edge.getDownstreamNodeId()).isEqualTo(nodes.get("TABLE|1|ads|sales").getId()));
        assertThat(columnEdges).isEmpty();
        assertThat(stored.getContentHash()).isEqualTo(HASH_B);
    }

    @Test
    void olderOrOutOfOrderReportsAreStaleAndAnalyzerVersionBreaksTies() {
        service.report(simpleReport(10, 5, HASH_A));

        var older = service.report(simpleReport(10, 4, HASH_B));
        assertThat(older.getResult()).isEqualTo("STALE");
        assertThat(older.getStoredRevision()).isEqualTo(5L);
        assertThat(stored.getContentHash()).isEqualTo(HASH_A);

        LineageReportReqDTO upgraded = simpleReport(10, 5, HASH_B);
        upgraded.setAnalyzerVersion(2);
        assertThat(service.report(upgraded).getResult()).isEqualTo("ACCEPTED");
        assertThat(stored.getAnalyzerVersion()).isEqualTo(2);

        LineageReportReqDTO downgraded = simpleReport(10, 5, HASH_A);
        downgraded.setAnalyzerVersion(1);
        var stale = service.report(downgraded);
        assertThat(stale.getResult()).isEqualTo("STALE");
        assertThat(stale.getStoredAnalyzerVersion()).isEqualTo(2);

        LineageReportReqDTO newerRevisionOldAnalyzer = simpleReport(10, 6, HASH_A);
        assertThat(service.report(newerRevisionOldAnalyzer).getResult()).isEqualTo("ACCEPTED");
        assertThat(stored.getSourceRevision()).isEqualTo(6L);
        assertThat(stored.getAnalyzerVersion()).isEqualTo(1);
    }

    @Test
    void removedClearsEdgesIsIdempotentAndOnlyStrictlyNewerRevisionRestores() {
        service.report(simpleReport(10, 5, HASH_A));

        var removed = service.report(removed(10, 5, 1));
        assertThat(removed.getResult()).isEqualTo("ACCEPTED");
        assertThat(tableEdges).isEmpty();
        assertThat(columnEdges).isEmpty();
        assertThat(stored.getStatus()).isEqualTo("REMOVED");
        assertThat(stored.getTableEdgeCount()).isZero();

        assertThat(service.report(removed(10, 5, 1)).getResult()).isEqualTo("NOOP");
        assertThat(service.report(removed(10, 2, 1)).getResult()).isEqualTo("NOOP");
        assertThat(service.report(simpleReport(10, 5, HASH_A)).getResult()).isEqualTo("STALE");
        assertThat(tableEdges).isEmpty();

        var restored = service.report(simpleReport(10, 6, HASH_A));
        assertThat(restored.getResult()).isEqualTo("ACCEPTED");
        assertThat(stored.getStatus()).isEqualTo("SUCCESS");
        assertThat(tableEdges).hasSize(1);
    }

    @Test
    void removedIgnoresRevisionButKeepsTheHigherStoredRevision() {
        service.report(simpleReport(10, 8, HASH_A));

        var removed = service.report(removed(10, 3, 1));

        assertThat(removed.getResult()).isEqualTo("ACCEPTED");
        assertThat(removed.getStoredRevision()).isEqualTo(8L);
        assertThat(stored.getStatus()).isEqualTo("REMOVED");
        assertThat(tableEdges).isEmpty();
        assertThat(service.report(simpleReport(10, 7, HASH_B)).getResult()).isEqualTo("STALE");

        var newerRemoval = service.report(removed(10, 9, 1));
        assertThat(newerRemoval.getResult()).isEqualTo("ACCEPTED");
        assertThat(stored.getSourceRevision()).isEqualTo(9L);
    }

    @Test
    void firstEverRemovedReportCreatesARemovedSource() {
        var resp = service.report(removed(11, 0, 1));
        assertThat(resp.getResult()).isEqualTo("ACCEPTED");
        assertThat(stored.getStatus()).isEqualTo("REMOVED");
        assertThat(stored.getSourceRevision()).isZero();
        assertThat(service.report(simpleReport(11, 1, HASH_A)).getResult()).isEqualTo("ACCEPTED");
    }

    @Test
    void sharedNodesAreReusedAndMissingDisplayNamesFilled() {
        service.report(simpleReport(10, 1, HASH_A));
        Long odsId = nodes.get("TABLE|1|ods|orders").getId();
        LineageReportReqDTO next = report(10, 2, 1, HASH_B);
        var ods = table(1, "ODS", "ORDERS");
        ods.setDisplayName("订单");
        next.getTableEdges().add(tableEdge(ods, table(1, "dwd", "orders")));

        service.report(next);

        assertThat(nodes.get("TABLE|1|ods|orders").getId()).isEqualTo(odsId);
        assertThat(nodes.get("TABLE|1|ods|orders").getDisplayName()).isEqualTo("订单");
        assertThat(nodes).hasSize(2);
    }

    @Test
    void invalidReportFailsBeforeAnyWrite() {
        LineageReportReqDTO invalid = simpleReport(10, 1, HASH_A);
        invalid.getColumnEdges().get(0).setTransformSubtype("FILTER");

        assertThatThrownBy(() -> service.report(invalid)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(ErrorCodeConstants.LINEAGE_REPORT_INVALID.getCode()));
        assertThat(stored).isNull();
        verifyNoInteractions(tableEdgeMapper);
    }

    @Test
    void revisionComparisonIsLexicographic() {
        LineageSourceDO source = new LineageSourceDO();
        source.setSourceRevision(5L);
        source.setAnalyzerVersion(2);
        assertThat(LineageReportServiceImpl.compareRevision(5, 2, source)).isZero();
        assertThat(LineageReportServiceImpl.compareRevision(5, 3, source)).isPositive();
        assertThat(LineageReportServiceImpl.compareRevision(6, 1, source)).isPositive();
        assertThat(LineageReportServiceImpl.compareRevision(4, 9, source)).isNegative();
    }

}
