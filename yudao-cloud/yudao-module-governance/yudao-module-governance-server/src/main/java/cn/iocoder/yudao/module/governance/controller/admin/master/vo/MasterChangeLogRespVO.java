package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "管理后台 - MasterChangeLogRespVO")
@Data
public class MasterChangeLogRespVO {

    private Long id;

    private Long changeId;

    private Long modelId;

    private String modelName;

    private String dataId;

    private String changeType;

    private String propertyName;

    private String beforeValue;

    private String afterValue;

    private Long changedBy;

    private String changedByName;

    private LocalDateTime changedAt;
}
