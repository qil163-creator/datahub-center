package cn.iocoder.yudao.module.governance.service.master.match;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 主数据相似匹配规则（已校验的不可变快照）。
 *
 * @param fields         参与打分的字段、算法与权重
 * @param blockingFields 分块字段：只有这些字段规范化后完全相同的记录才互相比较，用于控制比较量
 * @param threshold      相似度达到该值的两条记录视为疑似重复（0.5～1）
 * @param survivorship   字段取值规则（字段名 → 规则），未配置的字段取保留记录的值
 * @param maxRows        单次匹配允许读取的最大记录数
 */
public record MasterMatchConfig(List<Field> fields, List<String> blockingFields, BigDecimal threshold,
                                Map<String, Survivorship> survivorship, int maxRows) {

    public static final int DEFAULT_MAX_ROWS = 20_000;
    public static final int MAX_ROWS_LIMIT = 100_000;
    /** 单次匹配的两两比较上限；超出时要求配置分块字段 */
    public static final long MAX_COMPARISONS = 5_000_000L;
    /** 疑似重复组的成员上限；更大的簇通常说明规则过宽，跳过并提示 */
    public static final int MAX_GROUP_SIZE = 20;
    public static final BigDecimal MIN_THRESHOLD = new BigDecimal("0.5");

    /** 字段比较算法 */
    public enum Algorithm {
        /** 规范化（全半角、大小写、首尾及连续空白）后完全相同为 1，否则为 0 */
        EXACT,
        /** Jaro-Winkler 相似度，适合名称、简称等前缀稳定的文本 */
        TEXT,
        /** 编辑距离相似度，适合地址、证照号等存在错漏字符的文本 */
        EDIT,
        /** 数值接近度：1 - |a-b| / max(|a|,|b|) */
        NUMERIC
    }

    /** 黄金记录字段取值规则 */
    public enum Survivorship {
        /** 取保留记录的值；保留记录为空时用其他记录的第一个非空值补全 */
        SURVIVOR,
        /** 取内容最完整（最长）的值 */
        MOST_COMPLETE,
        /** 取出现次数最多的值 */
        MOST_FREQUENT,
        /** 取最大值 */
        MAX,
        /** 取最小值 */
        MIN
    }

    public record Field(String name, Algorithm algorithm, int weight) {
    }

    public MasterMatchConfig {
        fields = List.copyOf(fields);
        blockingFields = List.copyOf(blockingFields);
        survivorship = Map.copyOf(survivorship);
    }

}
