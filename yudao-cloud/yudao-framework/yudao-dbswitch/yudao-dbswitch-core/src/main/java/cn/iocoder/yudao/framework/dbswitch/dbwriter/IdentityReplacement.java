package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import java.util.List;
import java.util.Objects;

/** 由组件结构 DDL 生成器准备的仅运行时替换，绝不接受原始用户 SQL。 */
public record IdentityReplacement(String schema, String table, Mode mode, List<String> statements) {

    public enum Mode {RECREATE, TRUNCATE}

    /**
     * 校验构造参数并初始化 {@code IdentityReplacement}。
     */
    public IdentityReplacement {
        Objects.requireNonNull(mode);
        statements = List.copyOf(statements);
        if (schema == null || schema.isBlank() || table == null || table.isBlank()
                || mode == Mode.TRUNCATE && !statements.isEmpty() || mode == Mode.RECREATE && statements.isEmpty()
                || statements.size() > 2001 || statements.stream()
                .anyMatch(sql -> sql == null || sql.isBlank() || sql.length() > 1_048_576)
                || statements.stream().mapToLong(String::length).sum() > 4_194_304) {
            throw new IllegalArgumentException("Invalid structural identity replacement");
        }
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "IdentityReplacement[mode=" + mode + ", statements=" + statements.size() + "]";
    }
}
