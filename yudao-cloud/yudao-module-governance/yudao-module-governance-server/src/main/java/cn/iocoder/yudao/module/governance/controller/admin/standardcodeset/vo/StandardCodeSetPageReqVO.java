package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo;

import lombok.*;
import io.swagger.v3.oas.annotations.media.Schema;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 数据标准--标准码分页 Request VO")
@Data
public class StandardCodeSetPageReqVO extends PageParam {

    @Schema(description = "所属标准文件编号")
    private Long documentId;

    @Schema(description = "字典名称", example = "李四")
    private String name;

    @Schema(description = "状态", example = "1")
    private Short status;

    @Schema(description = "删除时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] deletedTime;

    @Schema(description = "类目及其全部子孙")
    private Long categoryId;

    @jakarta.validation.constraints.Pattern(regexp = "id|createTime")
    private String sortField = "createTime";

    @jakarta.validation.constraints.Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";

    private String type;

    private java.util.List<Long> categoryIds;
}
