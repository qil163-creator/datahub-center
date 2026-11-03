package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

import java.util.List;

@Schema(description = "管理后台 - 数据分类信息分页 Request VO")
@Data
public class SafetyCategoryPageReqVO extends PageParam {

    @Schema(description = "所属类目ID列表（含选中类目及其子孙类目，关联 dh_category_info.id）", example = "[1, 2, 3]")
    private List<Long> categoryIds;

    @Schema(description = "选中类目；由服务端展开后代，不信任客户端提供的层级编码")
    private Long categoryId;

    @Schema(description = "分类名称", example = "王五")
    @Size(max = 50)
    private String name;

    @Schema(description = "分类缩写", example = "芋艿")
    private String shortName;

    @Schema(description = "数据分级", example = "25576")
    private Integer dataLevelId;

    @Schema(description = "状态", example = "1")
    private Short status;

    @Pattern(regexp = "id|createTime")
    private String sortField;

    @Pattern(regexp = "asc|desc")
    private String sortOrder;

}
