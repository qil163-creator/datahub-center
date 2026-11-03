package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.object;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 表元数据业务/管理属性维护 Request VO
 *
 * 仅允许维护平台侧属性（采集永不覆盖）；每处修改记录 change_source=手工 的变更明细
 *
 * @author Li
 */
@Schema(description = "管理后台 - 表元数据业务属性维护 Request VO")
@Data
public class MetaTableUpdateBizReqVO {

    @Schema(description = "表元数据编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "表元数据编号不能为空")
    private Long id;

    @Schema(description = "读取详情时的修订号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "修订号不能为空，请重新加载详情")
    @Min(value = 0, message = "修订号不能小于零")
    private Integer bizRevision;

    @Schema(description = "平台注释", example = "站点基础信息（含经纬度整合字段）")
    private String customComment;

    @Schema(description = "业务名称", example = "站点基础信息表")
    private String businessName;

    @Schema(description = "业务定义")
    private String businessDefinition;

    @Schema(description = "所属分层", example = "DWD")
    private String layer;

    @Schema(description = "是否主表：0-否 1-是", example = "1")
    private Integer masterFlag;

    @Schema(description = "是否临时表：0-否 1-是")
    @Min(0)
    @jakarta.validation.constraints.Max(1)
    private Integer temporaryFlag;

    @Schema(description = "平台维护的存储类型，独立于采集存储引擎")
    @jakarta.validation.constraints.Size(max = 128)
    private String storageType;

    @Schema(description = "是否敏感表：0-否 1-是", example = "0")
    private Integer sensitiveFlag;

    @Schema(description = "主题域编号", example = "1")
    private Long subjectDomainId;

    @Schema(description = "主题域名称（展示快照）", example = "水利监测")
    private String subjectDomainName;

    @Schema(description = "技术负责人用户编号", example = "1")
    private Long techLeaderUserId;

    @Schema(description = "技术负责人电话", example = "13800000000")
    private String techLeaderPhone;

    @Schema(description = "业务负责人用户编号", example = "2")
    private Long businessLeaderUserId;

    @Schema(description = "业务负责人电话", example = "13800000001")
    private String businessLeaderPhone;

    @Schema(description = "责任部门编号", example = "100")
    private Long ownerDeptId;

    @Schema(description = "备注")
    private String remark;

}
