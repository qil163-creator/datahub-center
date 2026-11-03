package cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 从资产详情维护字段治理属性。
 */
@Data
public class DataAssetColumnUpdateReqVO {

    @NotNull(message = "资产编号不能为空")
    private Long assetId;

    @NotNull(message = "字段元数据编号不能为空")
    private Long id;

    @NotNull(message = "字段修订号不能为空，请重新读取当前字段")
    @Min(0)
    private Integer revision;

    private String customComment;

    private String businessName;

    private String businessDefinition;

    private String measuringUnit;

    private Integer standardElementId;

    private Long safetyLevelId;

    private Integer sensitiveFlag;

    private String remark;

}
