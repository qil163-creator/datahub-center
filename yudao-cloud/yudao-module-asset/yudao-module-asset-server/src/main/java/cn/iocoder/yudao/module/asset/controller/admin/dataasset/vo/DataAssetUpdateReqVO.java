package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 数据资产维护 Request VO")
@Data
public class DataAssetUpdateReqVO {

    @NotNull(message = "资产编号不能为空")
    private Long id;

    @NotBlank(message = "资产名称不能为空")
    @Size(max = 256, message = "资产名称不能超过 256 个字符")
    private String assetName;

    private Long categoryId;

    @Schema(description = "所属主题域编号，由服务端核验可见范围与名称")
    private Long subjectDomainId;

    @Valid
    private AssetRegistrationConfigVO registrationConfig;

    @Size(max = 2000, message = "资产说明不能超过 2000 个字符")
    private String description;

    private Long techLeaderUserId;

    private String techLeaderPhone;

    private Long businessLeaderUserId;

    private String businessLeaderPhone;

    private Long ownerDeptId;

    @Size(max = 512, message = "备注不能超过 512 个字符")
    private String remark;

    @Schema(description = "共享属性：0待核验 1无条件 2有条件 3不予共享")
    private Integer shareAttribute;

    @Size(max = 500)
    private String shareCondition;

    @Size(max = 64)
    private String updateCycle;

    @Size(max = 1000)
    private String businessPurpose;

    @Size(max = 64)
    private String contactName;

    @Size(max = 32)
    private String contactPhone;

    @Size(max = 1000)
    private String shareDenyReason;

    private Long providerDeptId;

    private Long sourceDeptId;

    @Size(max = 1000)
    private String dutyBasis;

    @Size(max = 500)
    private String updateRule;

    @Schema(description = "目录修订号，提交时用于 CAS")
    private Long expectedRevision;

}
