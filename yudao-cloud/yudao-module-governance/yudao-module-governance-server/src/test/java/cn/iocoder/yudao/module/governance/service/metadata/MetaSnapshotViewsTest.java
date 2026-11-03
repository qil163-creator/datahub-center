package cn.iocoder.yudao.module.governance.service.metadata;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaColumnRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object.MetaSnapshotCompareRespVO.ColumnChange;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableSnapshotDO;
import cn.iocoder.yudao.module.governance.service.metadata.core.MetaSnapshotViews;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaSnapshotViewsTest {

    @Test
    void arbitraryVersionsReportAddRemoveModifyAndStableColumns() {
        var before = snapshot(1, """
                [{"id":1,"columnName":"id","dataType":"BIGINT"},
                 {"id":2,"columnName":"amount","dataType":"INT"},
                 {"id":3,"columnName":"retired"}]
                """);
        var after = snapshot(2, """
                [{"id":1,"columnName":"id","dataType":"BIGINT"},
                 {"id":2,"columnName":"total","dataType":"DECIMAL"},
                 {"id":4,"columnName":"fresh"}]
                """);
        var result = MetaSnapshotViews.compare(before, after);
        assertThat(result.getAddedCount()).isEqualTo(1);
        assertThat(result.getRemovedCount()).isEqualTo(1);
        assertThat(result.getModifiedCount()).isEqualTo(1);
        assertThat(result.getColumnChanges()).extracting(ColumnChange::getChangeType)
                .containsExactly("UNCHANGED", "MODIFIED", "REMOVED", "ADDED");
        assertThat(result.getColumnChanges().get(1).getChanges()).extracting(change -> change.getProperty())
                .containsExactly("columnName", "dataType");
        var reverse = MetaSnapshotViews.compare(after, before);
        assertThat(reverse.getAddedCount()).isEqualTo(result.getRemovedCount());
        assertThat(reverse.getRemovedCount()).isEqualTo(result.getAddedCount());
    }

    @Test
    void replacementWithSameNameIsNotAnUpdateToOldIdentity() {
        var result = MetaSnapshotViews.compare(snapshot(1, "[{\"id\":1,\"columnName\":\"same\"}]"),
                snapshot(2, "[{\"id\":2,\"columnName\":\"same\"}]"));
        assertThat(result.getColumnChanges()).extracting(ColumnChange::getChangeType)
                .containsExactly("REMOVED", "ADDED");
    }

    @Test
    void explicitlyImportedNameIdentityIgnoresSourceInternalIdsButPreservesEvidence() {
        var before = importedSnapshot(1, "[{\"id\":1,\"resourceUrn\":\"urn:old\",\"columnName\":\"amount\",\"dataType\":\"DECIMAL\"}]");
        var after = importedSnapshot(2, "[{\"id\":99,\"resourceUrn\":\"urn:new\",\"columnName\":\"amount\",\"dataType\":\"DECIMAL\"}]");
        var result = MetaSnapshotViews.compare(before, after);
        assertThat(result.getModifiedCount()).isZero();
        assertThat(result.getColumnChanges()).extracting(ColumnChange::getChangeType).containsExactly("UNCHANGED");
        assertThat(MetaSnapshotViews.number(result.getColumnChanges().getFirst().getBefore().get("id"))).isEqualTo(1L);
        assertThat(MetaSnapshotViews.number(result.getColumnChanges().getFirst().getAfter().get("id"))).isEqualTo(99L);
        var ordinary = snapshot(2, "[{\"id\":99,\"columnName\":\"amount\",\"dataType\":\"DECIMAL\"}]");
        assertThat(MetaSnapshotViews.compare(before, ordinary).getColumnChanges())
                .extracting(ColumnChange::getChangeType).containsExactly("REMOVED", "ADDED");
    }

    @Test
    void importedIdentityStillDetectsStructuralChangesAndRejectsDuplicateNames() {
        var result = MetaSnapshotViews.compare(importedSnapshot(1, "[{\"id\":1,\"columnName\":\"amount\",\"length\":10}]"),
                importedSnapshot(2, "[{\"id\":99,\"columnName\":\"amount\",\"length\":20}]"));
        assertThat(result.getModifiedCount()).isEqualTo(1);
        assertThat(result.getColumnChanges().getFirst().getChanges()).extracting(change -> change.getProperty()).containsExactly("length");
        assertThatThrownBy(() -> MetaSnapshotViews.compare(importedSnapshot(1,
                "[{\"id\":1,\"columnName\":\"same\"},{\"id\":2,\"columnName\":\"same\"}]"), importedSnapshot(2, "[]")))
                .isInstanceOf(ServiceException.class);
    }

    private MetaTableSnapshotDO importedSnapshot(long id, String columns) {
        var snapshot = snapshot(id, columns);
        snapshot.setSnapshot(snapshot.getSnapshot().replace("{\"table\":", "{\"source\":\"qdata\",\"comparisonIdentity\":\"COLUMN_NAME\",\"table\":"));
        return snapshot;
    }

    @Test
    void frozenFieldKeepsLosslessIdentityAndDropsMaintenanceRevision() {
        var target = new MetaColumnRespVO();
        target.setRevision(9);
        MetaSnapshotViews.applyColumnStructure(target, Map.of("id", "9007199254740993", "columnName", "id"));
        assertThat(target.getId()).isEqualTo(9007199254740993L);
        assertThat(target.getRevision()).isNull();
        assertThat(target.getReadOnly()).isTrue();
        assertThat(target.getDataType()).isNull();
    }

    @Test
    void malformedAndDuplicateIdentityCannotPretendToBeAnEmptyDiff() {
        assertThatThrownBy(() -> MetaSnapshotViews.compare(snapshot(1, "[{\"id\":1}]"), snapshot(2, "[]")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MetaSnapshotViews.compare(snapshot(1,
                "[{\"id\":1,\"columnName\":\"a\"},{\"id\":1,\"columnName\":\"b\"}]"), snapshot(2, "[]")))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> MetaSnapshotViews.number("900719925474099312345"))
                .isInstanceOf(ServiceException.class);
    }

    private MetaTableSnapshotDO snapshot(long id, String columns) {
        return MetaTableSnapshotDO.builder().id(id).tableId(10L).structVersion("v" + id)
                .snapshot("{\"table\":{\"tableName\":\"orders\"},\"columns\":" + columns + "}").build();
    }
}
