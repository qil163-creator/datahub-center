package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "管理后台 - 同一表任意两个不可变结构快照的对比")
public class MetaSnapshotCompareRespVO {

    private Long tableId;

    private Long fromSnapshotId;

    private Long toSnapshotId;

    private String fromVersion;

    private String toVersion;

    private int addedCount;

    private int removedCount;

    private int modifiedCount;

    private List<PropertyChange> tableChanges;

    private List<ColumnChange> columnChanges;

    @Data
    public static class PropertyChange {

        private String property;

        private Object before;

        private Object after;
    }

    @Data
    public static class ColumnChange {

        private String columnName;

        @Schema(description = "ADDED/REMOVED/MODIFIED/UNCHANGED")
        private String changeType;

        private Map<String, Object> before;

        private Map<String, Object> after;

        private List<PropertyChange> changes;
    }
}
