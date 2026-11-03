package cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 数据标准--码值分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class StandardCodeItemPageReqVO extends PageParam {

    @Schema(description = "码值标签", example = "主汛期")
    @Size(max = 100, message = "码值标签长度不能超过 100 个字符")
    private String label;

    @Schema(description = "码值类型", example = "FSTP")
    @Size(max = 100, message = "码值类型长度不能超过 100 个字符")
    private String dictType;

    @Schema(description = "标准码表编号", example = "1024")
    private Integer codeSetId;

    @Schema(description = "父码值编号", example = "2048")
    private Integer parentId;

    @Schema(description = "业务时间；筛选此时有效的码值")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime businessTime;

    @Schema(description = "状态", example = "0")
    private Short status;

    @jakarta.validation.constraints.Pattern(regexp = "id|createTime")
    private String sortField = "createTime";

    @jakarta.validation.constraints.Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";
}
