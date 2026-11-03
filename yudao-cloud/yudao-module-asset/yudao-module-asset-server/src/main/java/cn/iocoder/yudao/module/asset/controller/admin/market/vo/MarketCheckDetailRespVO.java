package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "管理后台 - 服务审批只读详情；预览是导入快照，不产生访问授权")
public class MarketCheckDetailRespVO {

    private MarketCheckTicketRespVO ticket;

    private ResourceSummary resource;

    private List<Column> columns = List.of();

    private Preview preview;

    private ApiDocument apiDocument;

    private Boolean canPreview = false;

    private Boolean canInvoke = false;

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Preview extends DatabasePreviewRespDTO {

        private String total;
    }

    @Data
    public static class ResourceSummary {

        private String sourceRecordId;

        private String assetName;

        private String marketName;

        private String marketCategoryName;

        private String sourceDeptName;

        private String ownerDeptName;

        private Integer assetType;

        private String frequency;

        private List<Integer> deliveryTypes;

        private String rowCount;

        private LocalDateTime createTime;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Column extends MetadataColumnRespDTO {

        private String creatorName;

        private LocalDateTime createTime;

        private String codeTableName;

        private String safetyLevelName;
    }

    @Data
    public static class ApiDocument {

        private String name;

        private String method;

        private String path;

        private String resultType;

        private List<Parameter> requestParams;

        private List<Parameter> responseFields;

        private Object returnData;
    }

    @Data
    public static class Parameter {

        private String name;

        private Boolean required;

        private String type;

        private String description;

        private Object example;

        private Object defaultValue;
    }
}
