package cn.iocoder.yudao.framework.dbswitch.common.util;


import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;

import java.util.Arrays;
import java.util.List;

/**
 * 基于正则的名称替换工具类
 */
public final class PatterNameUtils {

    /**
     * 根据正则名称的转换函数
     *
     * @param originalName   原始名称
     * @param patternMappers 替换的正则规则列表
     * @return 替换后的名称
     */
    public static String getFinalName(String originalName, List<PatternMapper> patternMappers) {
        if (null == originalName) {
            return null;
        }

        String targetName = originalName;
        if (null != patternMappers && !patternMappers.isEmpty()) {
            for (PatternMapper mapper : patternMappers) {
                String fromPattern = mapper.getFromPattern();
                String toValue = mapper.getToValue();
                if (null == fromPattern) {
                    continue;
                }
                if (null == toValue) {
                    toValue = "";
                }
                //如果是纯字段映射，直接等于toValue即可
                if (fromPattern.equals(originalName)) {
                    return toValue;
                } else {
                    targetName = targetName.replaceAll(fromPattern, toValue);
                }
            }
        }
        return targetName;
    }

    /**
     * 测试函数
     */
    public static void main(String[] args) {
        // 添加前缀和后缀
        System.out.println(getFinalName(
                "start_time",
                Arrays.asList(new PatternMapper("start_time", "Actaul_Start_Time"),
                        new PatternMapper("end_time", "Actaul_End_Time")))
        );

        // 匹配的名字替换
        System.out.println(getFinalName(
                "hello",
                Arrays.asList(new PatternMapper("hello", "new_hello")))
        );

        // 不匹配的名字不替换
        System.out.println(getFinalName(
                "test",
                Arrays.asList(new PatternMapper("hello", "new_hello")))
        );
    }

}
