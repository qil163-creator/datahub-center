package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import cn.idev.excel.annotation.ExcelIgnoreUnannotated;
import cn.idev.excel.annotation.ExcelProperty;
import cn.iocoder.yudao.framework.excel.core.annotations.DictFormat;
import cn.iocoder.yudao.framework.excel.core.convert.DictConvert;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 脱敏规则（主表）；参数演进由版本表承载 Response VO")
@Data
@ExcelIgnoreUnannotated
public class SafetyRuleRespVO {

    @Schema(description = "脱敏规则编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "30410")
    @ExcelProperty("脱敏规则编号")
    private Long id;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "王五")
    @ExcelProperty("规则名称")
    private String ruleName;

    @Schema(description = "脱敏方式", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty(value = "脱敏方式", converter = DictConvert.class)
    @DictFormat("method_code") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private String methodCode;

    @Schema(description = "规则来源", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "规则来源", converter = DictConvert.class)
    @DictFormat("source_type") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private String sourceType;

    @Schema(description = "当前生效的规则", example = "7824")
    @ExcelProperty("当前生效的规则")
    private Long currentVerId;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @ExcelProperty(value = "状态", converter = DictConvert.class)
    @DictFormat("common_status") // TODO 代码优化：建议设置到对应的 DictTypeConstants 枚举类中
    private Short status;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("显示顺序")
    private Integer sort;

    @Schema(description = "规则描述", example = "你猜")
    @ExcelProperty("规则描述")
    private String description;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @ExcelProperty("创建时间")
    private LocalDateTime createTime;

    @Schema(description = "当前版本号", example = "1")
    private Integer currentVersionNo;

    @Schema(description = "脱敏参数（当前生效版本的配置）")
    private Map<String, Object> config;

    @Schema(description = "替换明细列表，仅内容替换（REPLACE/CONTENT_REPLACE）方式返回")
    private List<SafetyReplaceItemVO> replaceItems;

}
