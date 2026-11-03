package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 主数据模型分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class MasterModelPageReqVO extends PageParam {

    @Schema(description = "模型名称或物理表名")
    private String name;

    @Schema(description = "实体模型编码（物理表名）")
    @Size(max = 100)
    private String tableName;

    private Long domainId;

    @Min(0)
    @Max(2)
    private Integer status;

    @Schema(description = "创建时间范围")
    @Size(min = 2, max = 2)
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;
}
