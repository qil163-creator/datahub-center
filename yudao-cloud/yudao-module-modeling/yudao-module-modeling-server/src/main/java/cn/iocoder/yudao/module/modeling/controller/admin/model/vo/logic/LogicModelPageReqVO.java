package cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "管理后台 - 逻辑模型分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
public class LogicModelPageReqVO extends PageParam {

    @Schema(description = "模型名称或模型编码（模糊）", example = "aaa")
    private String keyword;

    @Schema(description = "中文名称（模糊）", example = "降水量表")
    private String name;

    @Schema(description = "英文名称（模糊）", example = "st_pptn")
    private String code;

    @Schema(description = "所属主题编号（含子主题）", example = "1")
    private Long dataDomainId;

    @Schema(description = "创建方式：FORWARD/REVERSE", example = "FORWARD")
    private String createMode;

    @Schema(description = "状态：DRAFT/PUBLISHED/DISCARDED", example = "PUBLISHED")
    private String status;

    @Schema(description = "分类确认状态：UNCLASSIFIED/CONFIRMED", example = "UNCLASSIFIED")
    private String classificationStatus;

    @Schema(description = "所属数仓分层编号", example = "6")
    private Long layerId;

    @Schema(description = "所属主题域编号", example = "1")
    private Long subjectDomainId;

    @Schema(description = "表类型：SOURCE/DETAIL/DIMENSION/SUMMARY/APPLICATION", example = "DETAIL")
    private String tableType;

    @Schema(description = "业务分类编号")
    private Long bizCategoryId;

    @Schema(description = "关联标准文件编号，精确筛选直接引用该标准的逻辑模型")
    @Positive(message = "标准文件编号必须为正数")
    private Long documentId;

}
