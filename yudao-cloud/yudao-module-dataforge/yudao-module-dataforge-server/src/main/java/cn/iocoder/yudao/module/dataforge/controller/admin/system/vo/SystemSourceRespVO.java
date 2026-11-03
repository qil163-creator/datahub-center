package cn.iocoder.yudao.module.dataforge.controller.admin.system.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 来源系统 Response VO")
@Data
@ExcelIgnoreUnannotated
public class SystemSourceRespVO {

    @Schema(description = "主键", requiredMode = Schema.RequiredMode.REQUIRED, example = "20386")
    @ExcelProperty("主键")
    private Long id;

    @Schema(description = "系统名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "芋艿")
    @ExcelProperty("系统名称")
    private String name;

    @Schema(description = "系统图标")
    @ExcelProperty("系统图标")
    private String icon;

    @Schema(description = "系统类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    @ExcelProperty(value = "系统类型", converter = DictConvert.class)
    @DictFormat("system_source_type") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private String type;

    @Schema(description = "负责人ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "22442")
    @ExcelProperty("负责人ID")
    private Long ownerUserId;

    @Schema(description = "说明", example = "随便")
    @ExcelProperty("说明")
    private String description;

    @Schema(description = "系统地址", example = "https://www.iocoder.cn")
    @ExcelProperty("系统地址")
    private String url;

    @Schema(description = "排序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("排序")
    private Integer sortOrder;

    @Schema(description = "所属部门id", requiredMode = Schema.RequiredMode.REQUIRED, example = "25154")
    @ExcelProperty("所属部门id")
    private Short deptId;

    @Schema(description = "状态：0停用 1启用", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "状态：0停用 1启用", converter = DictConvert.class)
    @DictFormat("common_status") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private Short status;

    @Schema(description = "备注", example = "你说的对")
    @ExcelProperty("备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("更新时间")
    private LocalDateTime updateTime;

}
