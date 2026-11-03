package cn.iocoder.yudao.module.dataforge.enums.fill;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 在线填报-合并策略枚举（模板版本发布时固化，运行期不可由前端改变）
 *
 * @author Li
 */
@Getter
@AllArgsConstructor
public enum FillMergeStrategyEnum {

    APPEND("APPEND", "追加：每次上报形成新历史记录，不覆盖已有数据"),
    UPSERT("UPSERT", "更新插入：按业务键新增或更新，业务键必须含组织与所属期维度"),
    REPLACE_SCOPE("REPLACE_SCOPE", "范围替换：在事务内替换 组织+所属期 范围内的全部数据");

    private final String strategy;

    private final String name;

    /**
     * 根据输入构造填报合并策略。
     *
     * @param strategy 策略
     * @return 填报合并策略
     */
    public static FillMergeStrategyEnum of(String strategy) {
        return Arrays.stream(values())
                .filter(item -> item.getStrategy().equals(strategy))
                .findFirst().orElse(null);
    }

}
