package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldContract;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 原生引擎子流程边界的静态字段契约校验；无法由 Catalog 类型证明的约束明确拒绝。 */
public final class EtlNativeBoundaryCompiler {
    private static final Pattern DECIMAL = Pattern.compile("(?:DECIMAL|NUMERIC)\\((\\d+),\\s*(\\d+)\\)");
    private static final Set<String> INTEGER = Set.of("TINYINT", "SMALLINT", "INT", "INTEGER", "INT2", "INT4");
    private static final Set<String> LONG = Set.of("BIGINT", "INT8", "BIGSERIAL", "SERIAL8");
    private static final Set<String> APPROXIMATE = Set.of("FLOAT", "FLOAT4", "FLOAT8", "REAL", "DOUBLE", "DOUBLE PRECISION");

    private EtlNativeBoundaryCompiler() { }

    /** 保持全部上游列和值不变，仅在所有可能的行均符合声明时允许原生编译。 */
    public static void validate(JSONObject config, Map<String, String> sourceTypes) {
        for (var contract : EtlFieldContract.parseFields(config.getJSONArray("fields"))) {
            if (!sourceTypes.containsKey(contract.name())) throw invalid("子流程边界字段不存在：" + contract.name());
            // 当前原生字段形状不携带可空性，不能把 nullable=false 降为不检查的直通。
            if (Boolean.FALSE.equals(contract.nullable()))
                throw invalid("原生子流程尚不能证明字段非空约束：" + contract.name());
            String source = sourceTypes.get(contract.name());
            if ("Object".equals(contract.type())) continue;
            if (source == null || source.isBlank()) throw invalid("子流程边界字段类型未知：" + contract.name());
            String type = source.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            var decimal = DECIMAL.matcher(type);
            boolean isDecimal = decimal.matches() || Set.of("DECIMAL", "NUMERIC").contains(type);
            boolean integral = INTEGER.contains(type) || LONG.contains(type);
            boolean compatible = switch (contract.type()) {
                case "String" -> type.equals("STRING") || type.equals("TEXT") || type.equals("CHARACTER")
                        || type.equals("VARCHAR") || type.equals("CHAR") || type.equals("BPCHAR")
                        || type.matches("(?:VARCHAR|CHAR|CHARACTER VARYING|CHARACTER)\\(\\d+\\)");
                case "Integer" -> INTEGER.contains(type);
                case "Long" -> integral;
                case "Double", "Decimal" -> integral || isDecimal || APPROXIMATE.contains(type);
                case "Boolean" -> Set.of("BOOL", "BOOLEAN").contains(type);
                case "Date" -> type.equals("DATE") || type.startsWith("TIMESTAMP") || type.startsWith("TIME(") || type.equals("TIME");
                default -> false;
            };
            if (!compatible) throw invalid("子流程边界字段类型不匹配：" + contract.name());
            if (contract.precision() == null && contract.scale() == null) continue;
            int precision;
            int scale;
            if (decimal.matches()) {
                precision = bounded(decimal.group(1)); scale = bounded(decimal.group(2));
            } else if (integral) {
                precision = LONG.contains(type) ? 19 : Set.of("TINYINT").contains(type) ? 3
                        : Set.of("SMALLINT", "INT2").contains(type) ? 5 : 10;
                scale = 0;
            } else throw invalid("子流程边界精度需要明确的精确数值类型：" + contract.name());
            int declaredScale = contract.scale() == null ? scale : contract.scale();
            if (declaredScale < scale || contract.precision() != null
                    && precision + declaredScale - scale > contract.precision())
                throw invalid("子流程边界精度或 scale 无法覆盖上游类型：" + contract.name());
        }
    }

    private static int bounded(String value) {
        try {
            int number = Integer.parseInt(value);
            if (number < 0 || number > 1000) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException invalid) {
            throw invalid("子流程上游精确数值类型超过允许范围");
        }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
