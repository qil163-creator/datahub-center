package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 在线填报-Excel 导入预检 Response VO
 *
 * 预检不写草稿；前端确认时将 rows 原样回传 confirm 接口
 *
 * @author Li
 */
@Schema(description = "管理后台 - Excel 导入预检 Response VO")
@Data
public class FillExcelImportPreviewRespVO {

    @Schema(description = "解析出的数据行数（不含表头与全空行）", example = "120")
    private Integer totalCount;

    @Schema(description = "错误数", example = "3")
    private Integer errorCount;

    @Schema(description = "匹配到的模板列名")
    private List<String> matchedColumns;

    @Schema(description = "被忽略的 Excel 表头（未匹配到模板输入列）")
    private List<String> ignoredHeaders;

    @Schema(description = "逐行逐字段校验问题（displayRowNo 为 Excel 实际行号）")
    private List<FillSubmitRespVO.Issue> issues;

    @Schema(description = "解析后的数据行：fieldCode -> 文本值（确认导入时原样回传）")
    private List<Map<String, Object>> rows;

}
