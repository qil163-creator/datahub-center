package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.core.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 带版本和长度分帧的结构哈希。保留字面量中的空白、大小写及列与键的顺序。 */
public final class SchemaFingerprint {

    /**
     * 初始化 {@code SchemaFingerprint}。
     */
    private SchemaFingerprint() {
    }

    /**
     * 根据给定参数创建 {@code String} 实例。
     */
    public static String of(TableSchemaMetadata schema) {
        StringBuilder out = new StringBuilder("DBSWITCH_SCHEMA_V1");
        append(out, schema.provenance().product());
        append(out, schema.provenance().serverProduct());
        append(out, schema.provenance().serverVersion());
        attribute(out, schema.existence());
        for (ColumnDescription column : schema.columns()) {
            append(out, "COLUMN");
            append(out, column.getFieldName());
            append(out, column.getFieldType());
            ColumnMetadataEvidence facts = column.getMetadataEvidence();
            attribute(out, facts.nativeType());
            attribute(out, facts.defaultExpression());
            attribute(out, facts.nullable());
            attribute(out, facts.numericPrecision());
            attribute(out, facts.numericScale());
            attribute(out, facts.characterLength());
            attribute(out, facts.identity());
            attribute(out, facts.generatedExpression());
            attribute(out, facts.charset());
            attribute(out, facts.collation());
            if (facts.sequence().value() != null && facts.sequence().value()
                    .kind() == ColumnSequenceMetadata.Kind.OWNED_DEFAULT) {
                append(out, "OWNED_SEQUENCE_STRUCTURE");
                append(out, facts.sequence().value().structuralIdentity());
            }
        }
        append(out, "PRIMARY_KEY");
        schema.primaryKeys().forEach(key -> append(out, key));
        append(out, schema.additionalObjects().state());
        if (schema.additionalObjects().value() != null) {
            schema.additionalObjects().value().stream().sorted().forEach(value -> append(out, value));
        }
        try {
            return "v1:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(out.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 读取当前对象或目录条目中的指定属性。
     */
    private static void attribute(StringBuilder out, MetadataValue<?> value) {
        append(out, value.state());
        append(out, value.value());
    }

    /**
     * 将给定内容追加到当前缓冲区，并更新累计大小。
     */
    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("-1:");
        } else {
            String text = value.toString();
            out.append(text.length()).append(':').append(text);
        }
    }
}
