package cn.iocoder.yudao.module.governance.service.profile;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 探查取值落库前的脱敏扩展点：最小/最大值与 Top 值在写入 {@code dh_governance_profile_*} 前必须经过本接口。
 *
 * <p>契约：</p>
 * <ul>
 *     <li>一次处理一次探查的全部字段，按字段名返回结果；每个结果与入参等长；</li>
 *     <li>{@code masked = false} 时调用方按原文保存；{@code masked = true} 时只保存返回的脱敏文本
 *     （元素为 {@code null} 表示不保存文本，只保留计数），绝不包含原文；</li>
 *     <li>实现必须失败关闭；调用方在实现抛异常、缺少某字段结果或长度不符时，对相应字段不保存任何取值；</li>
 *     <li>不得在持锁事务内调用，不得记录原始取值日志。</li>
 * </ul>
 *
 * @author Li
 */
public interface ProfileValueMasker {

    /** 脱敏原因：元数据敏感标注 / 安全分级 / 已确认打标命中，但脱敏服务未处理 */
    String REASON_METADATA = "METADATA";
    /** 脱敏原因：脱敏服务调用失败或结果不完整（失败关闭） */
    String REASON_MASK_FAILED = "MASK_FAILED";

    /**
     * 对一次探查的全部字段取值做落库前处理。
     *
     * @param columns 各字段的敏感依据与原始取值 {@code [min, max, top1..topN]}（截断后，元素可为 null）
     * @return 按字段名的脱敏结果
     */
    Map<String, MaskedValues> mask(List<ColumnValues> columns);

    /**
     * 单字段输入。
     *
     * @param column 字段敏感依据
     * @param values 原始取值
     */
    record ColumnValues(ProfileColumnSensitivity column, List<String> values) {

    }

    /**
     * 单字段脱敏结果。
     *
     * @param masked 是否已脱敏；true 时 values 中只能是脱敏后文本或 null
     * @param values 与入参等长的可落库文本
     * @param reason 脱敏原因（RULE/UNCLASSIFIED/UNTRACEABLE/UNVERIFIED/METADATA/MASK_FAILED），未脱敏时为 null
     */
    record MaskedValues(boolean masked, List<String> values, String reason) {

        /**
         * 全部不保存文本、只保留计数的结果。
         */
        public static MaskedValues redacted(int size, String reason) {
            return new MaskedValues(true, Collections.nCopies(size, null), reason);
        }

    }

}
