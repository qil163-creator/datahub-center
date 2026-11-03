package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标准数据元 Excel 导入 VO
 *
 * @author Li
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ExcelIgnoreUnannotated
public class StandardElementImportExcelVO {

    @ExcelProperty("中文名称")
    private String name;

    @ExcelProperty("英文名称")
    private String engName;

    @ExcelProperty("字段类型")
    private String columnType;

    @ExcelProperty("数据元类目名称")
    private String categoryName;

    @ExcelProperty("数据长度")
    private Integer dataLength;

    @ExcelProperty("数据精度")
    private Integer dataPrecision;

    @ExcelProperty("小数位数")
    private Integer dataScale;

    @ExcelProperty("数据格式")
    private String dataFormat;

    @ExcelProperty("计量单位")
    private String measureUnit;

    @ExcelProperty("值域范围")
    private String valueRange;

    @ExcelProperty("是否可空(0-不可空 1-可空)")
    private Integer nullableFlag;

    @ExcelProperty("默认值")
    private String defaultValue;

    @ExcelProperty("描述")
    private String description;

    @ExcelProperty("备注")
    private String remark;

}
