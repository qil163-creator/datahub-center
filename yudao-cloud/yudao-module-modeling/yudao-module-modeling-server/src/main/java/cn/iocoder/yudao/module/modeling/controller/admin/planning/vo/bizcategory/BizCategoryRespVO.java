package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.bizcategory;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "管理后台 - 业务分类 Response VO")
@Data
public class BizCategoryRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "父级编号，0 表示顶级节点", example = "0")
    private Long parentId;

    @Schema(description = "业务分类名称", example = "水利工程管理业务")
    private String name;

    @Schema(description = "英文缩写", example = "WATEN")
    private String code;

    @Schema(description = "负责人用户编号", example = "1")
    private Long leaderUserId;

    @Schema(description = "负责人电话", example = "15365128327")
    private String leaderPhone;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "责任范围说明，不作为授权依据")
    private String responsibilityScope;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建者编号")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新者编号")
    private String updater;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "关联数据域编号列表（按 sort_order）")
    private List<Long> dataDomainIds;

    @Schema(description = "关联数据域摘要（按 sort_order）")
    private List<DataDomainSimple> dataDomains;

    @Schema(description = "关联数据域摘要")
    @Data
    public static class DataDomainSimple {

        @Schema(description = "数据域编号", example = "1")
        private Long id;

        @Schema(description = "数据域名称", example = "水利工程数据域")
        private String name;

        @Schema(description = "英文缩写", example = "WRE")
        private String code;
    }

}
