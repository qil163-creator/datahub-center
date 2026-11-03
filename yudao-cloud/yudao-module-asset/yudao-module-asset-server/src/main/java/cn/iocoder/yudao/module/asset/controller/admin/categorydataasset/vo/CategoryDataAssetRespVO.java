package cn.iocoder.yudao.module.asset.controller.admin.categorydataasset.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据资产类目管理 Response VO")
@Data
@ExcelIgnoreUnannotated
public class CategoryDataAssetRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "7137")
    @ExcelProperty("主键")
    private Long id;

    @Schema(description = "父级ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "27346")
    @ExcelProperty("父级ID")
    private Long parentId;

    @Schema(description = "类目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "张三")
    @ExcelProperty("类目名称")
    private String categoryName;

    @Schema(description = "层级编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("层级编码")
    private String categoryCode;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("排序")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private Short status;

    @Schema(description = "备注", example = "你猜")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

}
