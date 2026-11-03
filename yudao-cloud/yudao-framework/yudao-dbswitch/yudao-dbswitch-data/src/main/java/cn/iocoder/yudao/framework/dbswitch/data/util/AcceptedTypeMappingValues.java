package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformer;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TableTypeMappingPlan;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 执行已接受且带指纹的类型计划中的值转换；原有 JDBC 读取保持不变。 */
public final class AcceptedTypeMappingValues {

    private static final String TIME_POLICY = "SQLSERVER_TIME7_FLOOR_MICROS_V1; sourceQuantum=100ns; "
            + "targetQuantum=1000ns; rounding=FLOOR; maximumLoss=900ns; midnightCarry=false";

    private final Set<String> timeColumns;

    /**
     * 初始化 {@code AcceptedTypeMappingValues}。
     */
    private AcceptedTypeMappingValues(Set<String> timeColumns) {
        this.timeColumns = Set.copyOf(timeColumns);
    }

    /**
     * 判断当前路径是否要求目标元数据证据。
     */
    public boolean requiresTargetEvidence() {
        return !timeColumns.isEmpty();
    }

    /** 已接受的预览不得隐式绑定到精度更低的已有目标。 */
    public void requireTargetColumns(List<ColumnDescription> columns) {
        for (String name : timeColumns) {
            var matches = columns.stream().filter(column -> name.equals(column.getFieldName())).toList();
            if (matches.size() != 1) {
                throw new IllegalArgumentException("Quantized TIME target column is not unambiguous");
            }
            var column = matches.getFirst();
            var facts = column.getMetadataEvidence();
            if (!facts.provenance().nativeProduct(ProductTypeEnum.POSTGRESQL)
                    || !"time(6) without time zone".equals(facts.nativeType().value())
                    || column.getFieldType() != java.sql.Types.TIME) {
                throw new IllegalArgumentException(
                        "Quantized TIME plan requires the observed PostgreSQL TIME(6) target");
            }
        }
    }

    /**
     * 根据已经通过准入的结果构建当前对象。
     */
    public static AcceptedTypeMappingValues fromAccepted(TableTypeMappingPlan plan) {
        plan.requireAccepted();
        var columns = new HashSet<String>();
        for (var column : plan.columns()) {
            if (column.sourceProduct() != ProductTypeEnum.SQLSERVER || column.targetProduct() != ProductTypeEnum.POSTGRESQL
                    || !"time(7)".equals(column.sourceNativeType().toLowerCase(Locale.ROOT))
                    || !"time(6) without time zone".equals(column.targetNativeType())) {
                continue;
            }
            if (column.fidelity() != TypeFidelity.LOSSY || !plan.acceptedLossyColumns().contains(column.column())
                    || column.issues().stream().noneMatch(issue -> issue.code().equals("TIME_FRACTION_TRUNCATED")
                    && issue.detail().equals(TIME_POLICY))) {
                throw new IllegalArgumentException("TIME quantization requires its exact accepted execution policy");
            }
            columns.add(column.column());
        }
        return new AcceptedTypeMappingValues(columns);
    }

    /** 字段为物理目标名称，顺序与行值一致。 */
    public Object[] convert(List<String> targetFields, Object[] row) {
        if (timeColumns.isEmpty()) {
            return row;
        }
        if (row == null || row.length != targetFields.size() || new HashSet<>(
                targetFields).size() != targetFields.size()
                || !targetFields.containsAll(timeColumns)) {
            throw new BatchRejectedException("Accepted type mapping requires a complete unambiguous row layout");
        }
        Object[] converted = row.clone();
        for (int i = 0; i < targetFields.size(); i++) {
            if (!timeColumns.contains(targetFields.get(i)) || row[i] == null) {
                continue;
            }
            if (!(row[i] instanceof LocalTime value) || value.getNano() % 100 != 0) {
                throw new BatchRejectedException(
                        "Accepted TIME7 quantization requires an exact 100ns LocalTime carrier");
            }
            converted[i] = value.withNano(value.getNano() / 1000 * 1000);
        }
        return converted;
    }

    /** 先执行已有的用户转换；DIFF 期间只有新增行或源端行进入此适配器。 */
    public RecordTransformer forSourceRows(RecordTransformer preceding, Map<String, String> sourceToTarget) {
        if (timeColumns.isEmpty()) {
            return preceding;
        }
        var names = Map.copyOf(sourceToTarget);
        return (schema, table, fields, row) -> {
            Object[] transformed = preceding == null ? row : preceding.transform(schema, table, fields, row);
            List<String> targets = fields.stream().map(field -> {
                String target = names.get(field);
                if (target == null) {
                    throw new BatchRejectedException("Accepted type mapping has no physical target field");
                }
                return target;
            }).toList();
            return convert(targets, transformed);
        };
    }
}
