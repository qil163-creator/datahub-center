package cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "管理后台 - 业务限定响应")
public class BusinessConditionRespVO {

    private Long id;

    private String code;

    private String name;

    private Long dataDomainId;

    private Long subjectDomainId;

    private Long entityId;

    private String entityName;

    private Long ownerUserId;

    private String description;

    private String businessEntity;

    private String dataFreshness;

    private Long sourceModelId;


    private String configType;

    private String logicMode;

    private String expression;

    private String status;

    private Long revision;

    private String creator;

    private String updater;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String sourceModelName;

    private String sourceModelCode;


    private String ownerUserName;

    private String ownerUserPhone;

    private String creatorName;

    private String creatorPhone;

    private String updaterName;

    private String updaterPhone;

    private Long derivedIndicatorCount;
}
