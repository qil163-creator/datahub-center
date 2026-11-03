package cn.iocoder.yudao.module.dataforge.controller.admin.driver.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据源驱动管理 Response VO")
@Data
@ExcelIgnoreUnannotated
public class DatasourceDriverRespVO {

    @Schema(description = "驱动编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "16128")
    @ExcelProperty("驱动编号")
    private Long id;

    @Schema(description = "驱动编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("驱动编码")
    private String driverCode;

    @Schema(description = "驱动名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "赵六")
    @ExcelProperty("驱动名称")
    private String driverName;

    @Schema(description = "数据库产品类型：MYSQL、POSTGRESQL、ORACLE、HIVE、DORIS、CLICKHOUSE   字典数据", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "数据库产品类型：MYSQL、POSTGRESQL、ORACLE、HIVE、DORIS、CLICKHOUSE   字典数据", converter = DictConvert.class)
    @DictFormat("datahub_product_type") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private String productType;

    @Schema(description = "驱动来源：BUILTIN 内置驱动，UPLOAD 上传驱动    字典数据", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty(value = "驱动来源：BUILTIN 内置驱动，UPLOAD 上传驱动    字典数据", converter = DictConvert.class)
    @DictFormat("datahub_driver_source") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private String driverSource;

    @Schema(description = "驱动类名", requiredMode = Schema.RequiredMode.REQUIRED, example = "李四")
    @ExcelProperty("驱动类名")
    private String driverClassName;

    @Schema(description = "JDBC URL 模板", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("JDBC URL 模板")
    private String jdbcUrlTemplate;

    @Schema(description = "连接图标路径")
    @ExcelProperty("连接图标路径")
    private String icon;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("排序")
    private Integer sort;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private Short status;

    @Schema(description = "备注", example = "你说的对")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

}
