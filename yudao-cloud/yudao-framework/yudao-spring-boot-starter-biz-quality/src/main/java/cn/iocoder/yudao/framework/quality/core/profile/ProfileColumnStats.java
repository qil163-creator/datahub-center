package cn.iocoder.yudao.framework.quality.core.profile;

import java.math.BigDecimal;

/**
 * 单列聚合统计结果（基于抽样）。未对该类别生成的统计项为 {@code null}。
 *
 * @param column        探查列
 * @param nonNullCount  非空数
 * @param distinctCount 唯一值数
 * @param minValue      最小值文本（已截断至 {@link ProfileSqlGenerator#MAX_VALUE_LENGTH} 个字符）
 * @param maxValue      最大值文本（已截断）
 * @param avgLength     文本平均长度
 * @param minLength     文本最小长度
 * @param maxLength     文本最大长度
 * @param avgValue      数值平均值
 * @author Li
 */
public record ProfileColumnStats(ProfileColumn column, long nonNullCount, Long distinctCount,
                                 String minValue, String maxValue, BigDecimal avgLength,
                                 Long minLength, Long maxLength, BigDecimal avgValue) {

}
