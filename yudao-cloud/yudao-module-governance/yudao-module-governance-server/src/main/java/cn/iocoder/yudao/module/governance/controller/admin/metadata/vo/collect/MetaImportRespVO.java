package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据字典导入结果 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 数据字典导入结果 Response VO")
@Data
public class MetaImportRespVO {

    @Schema(description = "采集实例编号（触发方式=导入）", example = "1")
    private Long instanceId;

    @Schema(description = "涉及库数量", example = "2")
    private Integer dbCount;

    @Schema(description = "处理表数量", example = "10")
    private Integer tableCount;

    @Schema(description = "处理字段数量", example = "120")
    private Integer columnCount;

    @Schema(description = "表新增数", example = "8")
    private Integer tableAdd;

    @Schema(description = "表变更数", example = "2")
    private Integer tableUpdate;

    @Schema(description = "字段新增数", example = "100")
    private Integer columnAdd;

    @Schema(description = "字段变更数", example = "15")
    private Integer columnUpdate;

    @Schema(description = "字段删除数（导入按报送口径覆盖表结构时产生）", example = "0")
    private Integer columnDelete;

    @Schema(description = "非法行数（跳过）", example = "1")
    private Integer invalidRows;

    @Schema(description = "错误明细（最多 20 条）", example = "[\"第 5 行：字段名为空\"]")
    private List<String> errors = new ArrayList<>();

}
