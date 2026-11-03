package cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 标准码值树 Request VO")
@Data
public class StandardCodeItemTreeReqVO {

    @NotNull(message = "标准码表编号不能为空")
    private Integer codeSetId;

    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime businessTime;

    @Schema(description = "是否返回全部历史", defaultValue = "false")
    private Boolean includeHistory;
}
