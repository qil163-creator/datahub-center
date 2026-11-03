package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 简报模板新增/修改 Request VO")
@Data
public class QualityBriefTemplateSaveReqVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "模板名称")
    @NotBlank(message = "模板名称不能为空")
    @Size(max = 128, message = "模板名称长度不能超过 128")
    private String templateName;

    @Schema(description = "模板编码")
    @Size(max = 128)
    private String templateCode;

    @Schema(description = "评估周期：DAY/WEEK/MONTH")
    private String assessCycle;

    @Schema(description = "评估维度")
    @Size(max = 64)
    private String assessDim;

    @Schema(description = "排序")
    private Integer sortNo;

    @Schema(description = "状态：0-启用 1-停用")
    private Integer status;

    @Schema(description = "描述")
    @Size(max = 1024)
    private String description;

    @Schema(description = "段落配置（全量覆盖）")
    private List<Paragraph> paragraphs;

    @Schema(description = "简报模板段落")
    @Data
    public static class Paragraph {

        @Schema(description = "段落类型：TEXT正文/TABLE附表")
        private String paraType;

        @Schema(description = "排序码")
        private Integer sortNo;

        @Schema(description = "标题")
        @Size(max = 256)
        private String title;

        @Schema(description = "内容模板（含变量 ${totalOrg} 等）")
        private String contentText;

        @Schema(description = "关联规则集编号")
        private Long ruleSetId;

        @Schema(description = "附表列配置")
        private List<Column> columns;

    }

    @Schema(description = "简报模板附列")
    @Data
    public static class Column {

        @Schema(description = "字段名")
        @Size(max = 128)
        private String fieldName;

        @Schema(description = "列标题")
        @Size(max = 128)
        private String colTitle;

        @Schema(description = "数据字段")
        @Size(max = 128)
        private String dataField;

        @Schema(description = "格式")
        @Size(max = 64)
        private String format;

        @Schema(description = "排序")
        private Integer sortNo;

    }

}
