package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

/**
 * EXACT：源值域中的每个值均原样存储和回读，并保持相同的相等语义。
 * LOSSY：部分值或语义会改变；逐项列出损失，必须按列确认接受。
 * UNSUPPORTED：没有已验证的映射，或事实不完整；出现副作用前拒绝整表。
 */
public enum TypeFidelity {
    EXACT, LOSSY, UNSUPPORTED;

    /**
     * 选择当前比较范围中保真度最低的结果。
     */
    public TypeFidelity worst(TypeFidelity other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
