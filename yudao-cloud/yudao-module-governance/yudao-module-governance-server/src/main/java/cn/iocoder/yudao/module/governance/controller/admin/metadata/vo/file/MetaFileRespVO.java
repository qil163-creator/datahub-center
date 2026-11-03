package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.file;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Schema(description = "管理后台 - 文件元数据")
public class MetaFileRespVO {

    private String host;

    private String port;

    private String username;

    private String dbType;

    private Long id;

    private Long datasourceId;

    private String datasourceName;

    private Long sourceSystemId;

    private String sourceSystemName;

    private Long taskId;

    private Long instanceId;

    private String path;

    private String parentPath;

    private String name;

    private String fileType;

    private Long size;

    private java.time.LocalDateTime sourceModifiedTime;

    private java.time.LocalDateTime firstSeenTime;

    private java.time.LocalDateTime lastSeenTime;

    private Integer objectStatus;

    private String businessName;

    private String description;

    private Long ownerUserId;

    private Long techLeaderUserId;

    private String techLeaderPhone;

    private Long businessLeaderUserId;

    private String businessLeaderPhone;

    private Integer bizRevision;

    private String ownerName;

    private String techLeaderName;

    private String businessLeaderName;

    private String creatorName;

    private String creatorPhone;

    private String updaterName;

    private String updaterPhone;

    private java.time.LocalDateTime createTime;

    private java.time.LocalDateTime updateTime;
}
