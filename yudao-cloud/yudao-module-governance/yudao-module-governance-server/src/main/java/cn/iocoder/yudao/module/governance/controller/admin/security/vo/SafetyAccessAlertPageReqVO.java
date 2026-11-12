package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理后台 - 异常访问告警分页 Request VO
 */
@Schema(description = "管理后台 - 异常访问告警分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyAccessAlertPageReqVO extends PageParam {

    @Schema(description = "状态：OPEN/CONFIRMED/IGNORED", example = "OPEN")
    @Pattern(regexp = "OPEN|CONFIRMED|IGNORED", message = "状态不合法")
    private String status;

    @Schema(description = "规则：VOLUME/PLAINTEXT/OFF_HOURS/TABLE_SPREAD", example = "VOLUME")
    @Pattern(regexp = "VOLUME|PLAINTEXT|OFF_HOURS|TABLE_SPREAD", message = "规则不合法")
    private String ruleCode;

    @Schema(description = "访问用户编号", example = "1")
    private Long userId;
}
