package cn.iocoder.yudao.module.governance.controller.admin.categorydatameta.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 标准数据元类目管理 Response VO")
@Data
@ExcelIgnoreUnannotated
public class CategoryDatametaRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "16966")
    @ExcelProperty("主键")
    private Long id;

    @Schema(description = "父级ID", example = "0")
    private Long parentId;

    @Schema(description = "类目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "李四")
    @ExcelProperty("类目名称")
    private String categoryName;

    @Schema(description = "层级编码", example = "0,7")
    private String categoryCode;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("排序")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
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
