package cn.iocoder.yudao.module.governance.api.metadata.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 字段业务属性维护 RPC DTO。
 */
@Data
public class MetadataColumnUpdateReqDTO {

    @NotNull(message = "字段元数据编号不能为空")
    private Long id;

    @NotNull(message = "字段修订号不能为空，请重新读取当前字段详情")
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
