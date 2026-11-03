package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import lombok.*;

import java.util.*;

import io.swagger.v3.oas.annotations.media.Schema;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

@Schema(description = "管理后台 - 数据标准--标准数据元分页 Request VO")
@Data
public class StandardElementPageReqVO extends PageParam {

    @Schema(description = "所属标准文件编号")
    private Long documentId;

    @Schema(description = "数据元编码")
    private String code;

    @Schema(description = "中文名称", example = "张三")
    private String name;

    @Schema(description = "英文名称", example = "张三")
    private String engName;

    @Schema(description = "数据元类目ID列表（含选中类目及其子孙类目，关联 dh_foundation_category_datameta.id）", example = "[1, 2, 3]")
    private List<Long> categoryIds;

    @Schema(description = "关联标准码表编号", example = "1")
    private Integer codeSetId;

    @Schema(description = "生命周期状态：DRAFT-草稿 PUBLISHED-已发布 DEPRECATED-已废止", example = "PUBLISHED")
    private String lifecycleStatus;

    @Schema(description = "状态", example = "2")
    private Short status;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

    @Schema(description = "类目及其全部子孙")
    private Long categoryId;

    @jakarta.validation.constraints.Pattern(regexp = "id|createTime")
    private String sortField = "createTime";

    @jakarta.validation.constraints.Pattern(regexp = "asc|desc")
    private String sortOrder = "desc";

}
