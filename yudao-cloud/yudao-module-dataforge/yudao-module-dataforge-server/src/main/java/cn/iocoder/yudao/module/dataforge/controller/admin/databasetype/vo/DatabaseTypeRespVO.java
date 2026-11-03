package cn.iocoder.yudao.module.dataforge.controller.admin.databasetype.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 数据库类型定义 Response VO")
@Data
@ExcelIgnoreUnannotated
public class DatabaseTypeRespVO {

    @Schema(description = "当前部署注册的数据库产品；未注册时为空")
    private String productId;

    @Schema(description = "组件声明的可恢复游标目标产品，仍需执行前校验驱动、结构和键语义")
    private java.util.List<String> cursorTargets = java.util.List.of();

    @Schema(description = "按操作区分的能力状态，DECLARED 不代表通过兼容认证")
    private java.util.Map<String, Capability> capabilities = java.util.Map.of();

    public record Capability(String status, String reason) {

    }

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "24883")
    @ExcelProperty("主键")
    private Long id;

    @Schema(description = "数据源编码")
    @ExcelProperty("数据源编码")
    private String typeCode;

    @Schema(description = "数据源名称", example = "王五")
    @ExcelProperty("数据源名称")
    private String typeName;

    @Schema(description = "连接类型描述")
    @ExcelProperty("描述")
    private String description;

    @Schema(description = "连接分类")
    @ExcelProperty(value = "连接分类", converter = DictConvert.class)
    @DictFormat("category_code") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private String categoryCode;

    @Schema(description = "连接协议类型", example = "2")
    @ExcelProperty(value = "连接协议类型", converter = DictConvert.class)
    @DictFormat("protocol_type") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private String protocolType;

    @Schema(description = "连接图标路径")
    @ExcelProperty("连接图标路径")
    private String iconPath;

    @Schema(description = "数据源URL")
    @ExcelProperty("数据源URL")
    private String urlTemplates;

    @Schema(description = "默认端口")
    @ExcelProperty("默认端口")
    private String portTemplates;

    @Schema(description = "默认连接测试SQL")
    @ExcelProperty("默认连接测试SQL")
    private String validationQuery;

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
