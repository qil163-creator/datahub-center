package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.tag.MetaTagRespVO;

import java.util.List;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字段元数据 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 字段元数据 Response VO")
@Data
public class MetaColumnRespVO {

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String displaySnapshot;

    private String creator;

    private String creatorName;

    private String creatorPhone;

    private String updater;

    private String updaterName;

    private String updaterPhone;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @Schema(description = "定版字段不可提交维护")
    private Boolean readOnly;

    private List<MetaTagRespVO> tags;


    @Schema(description = "字段元数据编号", example = "1")
    private Long id;

    @Schema(description = "资源 URN", example = "urn:datahub:prod:column:1:1000")
    private String resourceUrn;

    @Schema(description = "表元数据编号", example = "1")
    private Long tableId;

    @Schema(description = "字段名", example = "basin_code")
    private String columnName;

    @Schema(description = "字段顺序", example = "1")
    private Integer ordinalPosition;

    @Schema(description = "源库字段注释", example = "流域编码")
    private String sourceComment;

    @Schema(description = "平台注释（平台维护）", example = "流域编码（按 SL 249 标准）")
    private String customComment;

    @Schema(description = "业务名称/中文名称", example = "流域编码")
    private String businessName;

    @Schema(description = "数据类型", example = "varchar")
    private String dataType;

    @Schema(description = "数据长度", example = "64")
    private Long dataLength;

    @Schema(description = "数据精度", example = "10")
    private Integer dataPrecision;

    @Schema(description = "小数位数", example = "2")
    private Integer dataScale;

    @Schema(description = "是否可空：0-否 1-是", example = "1")
    private Integer nullableFlag;

    @Schema(description = "默认值")
    private String defaultValue;

    @Schema(description = "是否主键：0-否 1-是", example = "0")
    private Integer pkFlag;

    @Schema(description = "是否外键：0-否 1-是", example = "0")
    private Integer fkFlag;

    @Schema(description = "是否唯一：0-否 1-是", example = "0")
    private Integer uniqueFlag;

    @Schema(description = "是否自增：0-否 1-是", example = "0")
    private Integer autoIncrementFlag;

    @Schema(description = "业务定义")
    private String businessDefinition;

    @Schema(description = "度量单位", example = "米")
    private String measuringUnit;

    @Schema(description = "标准数据元编号", example = "1")
    private Integer standardElementId;

    @Schema(description = "已确认落标映射编号")
    private Long standardMappingId;

    @Schema(description = "标准数据元编码")
    private String standardElementCode;

    @Schema(description = "标准数据元名称")
    private String standardElementName;

    @Schema(description = "安全分级编号", example = "2")
    private Long safetyLevelId;

    @Schema(description = "敏感/加密标注：0-否 1-是", example = "0")
    private Integer sensitiveFlag;

    @Schema(description = "内部修订号", example = "1")
    private Integer revision;

    @Schema(description = "对象状态：1-正常 2-待确认删除 3-已删除", example = "1")
    private Integer objectStatus;

    @Schema(description = "最近确认时间")
    private LocalDateTime lastSeenTime;

    @Schema(description = "备注")
    private String remark;

}
