package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 清洗规则在线测试 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 清洗规则在线测试 Response VO")
@Data
public class CleanRuleTestRespVO {

    @Schema(description = "策略标识", example = "REMOVE_WHITESPACE")
    private String strategyKey;

    @Schema(description = "清洗前行数", example = "3")
    private Integer beforeCount;

    @Schema(description = "清洗后行数", example = "2")
    private Integer afterCount;

    @Schema(description = "清洗后数据；精确小数和大整数在所有嵌套层级以十进制字符串返回")
    private List<Map<String, Object>> rows;

}
