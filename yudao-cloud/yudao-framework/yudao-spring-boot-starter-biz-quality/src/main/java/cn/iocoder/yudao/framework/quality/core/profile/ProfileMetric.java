package cn.iocoder.yudao.framework.quality.core.profile;

/**
 * 单遍聚合中每列可生成的统计项。
 *
 * @author Li
 */
public enum ProfileMetric {

    /** 非空数：COUNT(col) */
    NON_NULL,
    /** 唯一值数：COUNT(DISTINCT col) */
    DISTINCT,
    /** 最小值 */
    MIN,
    /** 最大值 */
    MAX,
    /** 文本平均长度（字符数） */
    AVG_LENGTH,
    /** 文本最小长度（字符数） */
    MIN_LENGTH,
    /** 文本最大长度（字符数） */
    MAX_LENGTH,
    /** 数值平均值 */
    AVG_VALUE

}
