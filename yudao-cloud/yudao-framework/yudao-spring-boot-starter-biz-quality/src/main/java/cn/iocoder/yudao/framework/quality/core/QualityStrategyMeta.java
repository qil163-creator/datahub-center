package cn.iocoder.yudao.framework.quality.core;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 质量策略元数据（供规则管理界面选择策略、展示配置示例）
 *
 * @author Li
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualityStrategyMeta {

    /** 策略标识（如 LENGTH_VALIDATION） */
    private String strategyKey;

    /** 策略名称 */
    private String strategyName;

    /** 策略说明 */
    private String description;

    /** 规则配置 JSON 示例 */
    private String configExample;

}
