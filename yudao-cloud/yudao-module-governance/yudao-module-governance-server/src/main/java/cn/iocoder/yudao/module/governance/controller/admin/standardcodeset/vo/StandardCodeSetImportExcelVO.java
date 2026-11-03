package cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标准码表/码值 Excel 导入 VO
 *
 * <p>每行代表一个码值，同一字典类型的行归属同一码表；
 * 码表不存在时自动创建（要求填写码表名称）。</p>
 *
 * @author Li
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ExcelIgnoreUnannotated
public class StandardCodeSetImportExcelVO {

    @ExcelProperty("码表名称")
    private String codeSetName;

    @ExcelProperty("字典类型")
    private String type;

    @ExcelProperty("码值键值")
    private String value;

    @ExcelProperty("码值标签")
    private String label;

    @ExcelProperty("排序")
    private Integer sort;

    @ExcelProperty("备注")
    private String remark;

}
