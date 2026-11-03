package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;

import java.util.Locale;
import java.util.Set;

/** 将连接器字段声明转为受管JSON行源共用的JDBC类型合同；不猜测输入字段与日期格式。 */
final class EtlNativeDeclaredColumns {
    private EtlNativeDeclaredColumns() { }
    static JSONArray parse(JSONArray declared) { return parse(declared, false); }
    static JSONArray parse(JSONArray declared, boolean responseOptions) {
        var columns = JSONUtil.createArray();
        if (declared == null || declared.isEmpty() || declared.size() > 1024) throw new IllegalArgumentException("请显式声明1至1024个返回字段");
        for (Object value : declared) {
            var column = JSONUtil.parseObj(value);
            for (String key : column.keySet()) if (!Set.of("name", "type", "key", "index", "comment", "length", "precision", "format").contains(key) && !(responseOptions && key.equals("defaultValue"))) throw new IllegalArgumentException("字段包含未适配参数");
            int type = switch (column.getStr("type", "STRING").toUpperCase(Locale.ROOT)) {
                case "STRING", "VARCHAR", "CHAR", "TEXT", "INTERNETADDRESS" -> 12; case "INTEGER", "INT", "SMALLINT", "TINYINT" -> 4;
                case "LONG", "BIGINT" -> -5; case "FLOAT", "DOUBLE", "NUMBER" -> 8; case "DECIMAL", "NUMERIC", "BIGNUMBER" -> 3;
                case "BOOLEAN" -> 16; case "DATE" -> 91; case "TIMESTAMP" -> 93;
                default -> throw new IllegalArgumentException("字段类型尚未适配");
            };
            Object length = column.get("length"), scale = column.get("precision");
            if (length == null || length.toString().isBlank()) length = type == 3 ? 38 : 0;
            if (scale == null || scale.toString().isBlank()) scale = type == 3 ? 18 : 0;
            if (type != 3 && !scale.toString().equals("0")) throw new IllegalArgumentException("非小数字段不能声明小数精度");
            var mapped = JSONUtil.createObj().set("fieldName", column.getStr("name")).set("fieldType", type).set("displaySize", length).set("precisionSize", scale);
            if (responseOptions) mapped.set("format", column.get("format")).set("defaultValue", column.get("defaultValue"));
            columns.add(mapped);
        }
        return columns;
    }
}
