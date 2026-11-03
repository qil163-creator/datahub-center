package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AssetImportRowLogRespVO {

    private String id;

    private String rowId;

    private Integer revision;

    private String actorName;

    private LocalDateTime createTime;

    private List<Change> changes;

    @Data
    public static class Change {

        private String columnName;

        private String columnLabel;

        private String oldValue;

        private String newValue;
    }
}
