package cn.iocoder.yudao.framework.dbswitch.common.entity;

import java.util.Objects;

/**
 * 基于正则表达式的批评替换实体定义
 */
public class PatternMapper {

    private String fromPattern;

    private String toValue;

    /**
     * 初始化 {@code PatternMapper}。
     */
    public PatternMapper() {
    }

    /**
     * 初始化 {@code PatternMapper}。
     */
    public PatternMapper(String fromPattern, String toValue) {
        this.fromPattern = fromPattern;
        this.toValue = toValue;
    }

    /**
     * 返回来源模式表达式。
     */
    public String getFromPattern() {
        return fromPattern;
    }

    /**
     * 设置来源模式表达式。
     */
    public void setFromPattern(String fromPattern) {
        this.fromPattern = fromPattern;
    }

    /**
     * 返回目标值。
     */
    public String getToValue() {
        return toValue;
    }

    /**
     * 设置目标值。
     */
    public void setToValue(String toValue) {
        this.toValue = toValue;
    }

    /**
     * 比较当前对象与给定对象是否具有相同的值。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PatternMapper that = (PatternMapper) o;
        return fromPattern.equals(that.fromPattern) && toValue.equals(that.toValue);
    }

    /**
     * 计算与值相等语义一致的哈希码。
     */
    @Override
    public int hashCode() {
        return Objects.hash(fromPattern, toValue);
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "PatternMapper{" +
                "fromPattern='" + fromPattern + '\'' +
                ", toValue='" + toValue + '\'' +
                '}';
    }

}
