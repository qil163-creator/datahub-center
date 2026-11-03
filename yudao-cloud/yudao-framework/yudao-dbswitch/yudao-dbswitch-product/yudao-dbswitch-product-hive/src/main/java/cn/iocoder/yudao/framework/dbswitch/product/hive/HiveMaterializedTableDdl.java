package cn.iocoder.yudao.framework.dbswitch.product.hive;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** 将数据物化到普通 ORC；刻意不作为原生表定义克隆。 */
public final class HiveMaterializedTableDdl {

    /**
     * 初始化 {@code HiveMaterializedTableDdl}。
     */
    private HiveMaterializedTableDdl() {
    }

    /**
     * 检查列定义是否携带 Hive 原生元数据证据。
     */
    public static boolean hasHiveEvidence(List<ColumnDescription> columns) {
        return columns.stream()
                .anyMatch(column -> column.getMetadataEvidence().provenance().product() == ProductTypeEnum.HIVE
                        && column.getMetadataEvidence().nativeType().state() == MetadataValue.State.VALUE);
    }

    /**
     * 拒绝当前实现未支持的 {@code create} 操作。
     */
    public static String create(IDatabaseInterface dialect, List<ColumnDescription> columns, String schema,
            String table, String remarks, boolean autoIncrement) {
        if (autoIncrement || columns.isEmpty() || columns.size() > 4096) {
            throw new UnsupportedOperationException(
                    "Hive materialization requires scalar columns without generated identity");
        }
        var mapper = new HiveLogicalTypeMapper();
        var names = new HashSet<String>();
        var sql = new StringBuilder("CREATE TABLE ").append(dialect.getQuotedSchemaTableCombination(schema, table))
                .append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            var column = columns.get(i);
            // 映射校验固定原生版本、目录方法、JDBC 空壳和参数边界。
            // 物化为相同原生类型时，FLOAT32 既有的舍入不算新增损失。
            if (column.getMetadataEvidence().nativeType().state() != MetadataValue.State.VALUE
                    || !HiveLogicalTypeMapper.verified(column.getMetadataEvidence().provenance())
                    || mapper.toLogical(column).fidelity() == TypeFidelity.UNSUPPORTED) {
                throw new UnsupportedOperationException(
                        "Hive materialization column evidence is unverified or non-scalar");
            }
            if (column.getFieldName() == null || !names.add(column.getFieldName().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Hive materialization columns must have unique physical names");
            }
            String nativeType = column.getMetadataEvidence().nativeType().value().toLowerCase(Locale.ROOT);
            if (i != 0) {
                sql.append(",\n");
            }
            sql.append(cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                    ProductTypeEnum.HIVE, column.getFieldName())).append(' ').append(nativeType);
            if (column.getRemarks() != null && !column.getRemarks().isEmpty()) {
                sql.append(" COMMENT ").append(literal(column.getRemarks()));
            }
        }
        sql.append("\n)");
        if (remarks != null && !remarks.isEmpty()) {
            sql.append(" COMMENT ").append(literal(remarks));
        }
        return sql.append(" STORED AS ORC").toString();
    }

    /**
     * 返回当前字面量。
     */
    private static String literal(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Hive DDL comment cannot contain NUL");
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n") + "'";
    }
}
