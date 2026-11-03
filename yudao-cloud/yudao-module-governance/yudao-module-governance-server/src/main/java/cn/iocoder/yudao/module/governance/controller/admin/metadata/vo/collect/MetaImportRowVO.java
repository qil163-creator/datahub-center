package cn.iocoder.yudao.module.governance.controller.admin.metadata.vo.collect;

import cn.idev.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 数据字典导入 Excel 行 VO（单 Sheet：每行一个字段，表级信息取该表首行）
 *
 * 面向政务无直连报送场景：委办局按模板填写数据字典，导入走与直连采集同一套 diff 引擎
 *
 * @author Li
 */
@Data
public class MetaImportRowVO {

    @ExcelProperty("库名")
    private String dbName;

    @ExcelProperty("表名")
    private String tableName;

    @ExcelProperty("表注释")
    private String tableComment;

    @ExcelProperty("字段名")
    private String columnName;

    @ExcelProperty("字段注释")
    private String columnComment;

    @ExcelProperty("数据类型")
    private String dataType;

    @ExcelProperty("长度")
    private Long dataLength;

    @ExcelProperty("精度")
    private Integer dataPrecision;

    @ExcelProperty("小数位")
    private Integer dataScale;

    @ExcelProperty("是否可空(是/否)")
    private String nullableText;

    @ExcelProperty("是否主键(是/否)")
    private String pkText;

    @ExcelProperty("是否自增(是/否)")
    private String autoIncrementText;

    @ExcelProperty("默认值")
    private String defaultValue;

}
