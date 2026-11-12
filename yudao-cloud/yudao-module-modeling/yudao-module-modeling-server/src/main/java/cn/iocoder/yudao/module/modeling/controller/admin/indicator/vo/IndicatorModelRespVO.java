package cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "管理后台 - 指标模型响应")
public class IndicatorModelRespVO {

    private Long id;

    private String code;

    private String name;

    private String indicatorType;

    private Long dataDomainId;

    private Long subjectDomainId;

    private Long entityId;

    private String entityName;

    private Long ownerUserId;

    private String description;

    private String definition;

    private String unit;

    private String dataFreshness;

    private String resultDataType;

    private Long sourceModelId;


    private String valueFieldUid;

    private String timeFieldUid;

    private String aggregation;

    private Long baseIndicatorId;

    private String timePeriod;

    private String businessCondition;

    @Schema(description = "复合指标公式")
    private String formula;

    @Schema(description = "引用本指标的复合指标数量")
    private Long compositeReferenceCount;

    private String status;

    private Long revision;

    private String creator;

    private String updater;

    private String ownerUserName;

    private String ownerUserPhone;

    private String creatorName;

    private String creatorPhone;

    private String updaterName;

    private String updaterPhone;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String sourceModelName;

    private String sourceModelCode;

    private Long derivedIndicatorCount;


    private String valueFieldName;

    private String timeFieldName;

    private String baseIndicatorName;

    @Schema(description = "只读业务表达式，仅展示定义，不执行 SQL 或计算任务")
    private String expression;
}

