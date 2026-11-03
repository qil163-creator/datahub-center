package cn.iocoder.yudao.framework.etl.core.compile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 设计器能力查询与编译入口共享的原生 SQL 组件声明，限制说明也作为 UI 提示来源。 */
public final class EtlNativeSqlCapabilities {
    private EtlNativeSqlCapabilities() { }

    /** 未出现的组件不可编译；空说明表示该组件的已声明参数不附加引擎限制。 */
    public static Map<String, String> componentLimitations(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        if (streaming && engine != EtlNativeSqlCompiler.Engine.FLINK) return Map.of();
        var result = new LinkedHashMap<String, String>();
        result.put("read-db", "需要管理员受管 Catalog 绑定；不支持自定义 SQL 或游标增量" + (streaming ? "；实时模式必须绑定持续产出的源" : ""));
        result.put("insert-db", "需要管理员受管 Catalog 绑定，仅向已有表追加写入，不支持清空、建表或覆盖");
        result.put("column-map", "类型与长度转换遵循原生引擎 CAST；Decimal 精度最多 38 位");
        result.put("add-constant", "支持 String、Integer、Long、Double、Boolean、Date；数值与日期须能解析");
        result.put("set-field-value", "");
        result.put("string-operation", "支持 Unicode 去空格、大小写和左右/双侧填充，填充长度最多 1000000");
        result.put("string-replace", "支持字面/Java 正则、整词、大小写及字段替换；Flink 正则替换及两引擎整词替换须绑定 DH_REGEX_REPLACE 受管函数");
        result.put("field-derive", "支持七种操作；表达式仅数值算术或字段插值；子串按引擎字符边界处理");
        result.put("calculator", "支持24种操作及顺序规则；Decimal(38,18)值域，日期按引擎时间戳解析");
        result.put("data-encrypt", "支持 MD5/SHA1/SHA256/SHA512/BASE64；AES/SM4/AES128/AES256/DES/3DES 需要受管函数与当前任务授权的密钥引用");
        result.put("data-decrypt", "支持 BASE64 和受管 AES/SM4/AES128/AES256/DES/3DES；需当前任务授权的密钥引用，摘要算法不可逆");
        result.put("id-generator", streaming ? "仅 UUID 模式，必须直接连接唯一表输出" : "支持 UUID 或具有显式排序及运行时唯一键验证的 SEQUENCE；必须直接连接唯一表输出");
        result.put("row-column", streaming ? "仅列转行，同一组字段必须类型一致" : "列转行需同类型字段；行转列需显式排序及运行时唯一键验证，按顺序末值覆盖");
        result.put("field-split", "支持字面/正则、包裹符、类型/长度/精度转换及日期格式；正则模式须绑定 DH_SPLIT_TOKEN 受管函数");
        result.put("data-filter", "");
        result.put("value-map", "保留原值策略要求字符串字段，输出为字符串");
        result.put("number-range", "要求数值输入，范围与常量精度最多38位，输出为字符串");
        result.put("data-condition", "支持 IF/ELSE 分流；ERROR 策略要求 ELSE 分支；每个分支均须连接输出");
        result.put("data-clean", "仅本地字段删除、TRIM/大小写格式化" + (streaming ? "；不支持实时去重" : "及整行去重；按键或忽略大小写去重须声明全序并校验唯一性，保留首条原值"));
        if (!streaming) {
            result.put("multi-join", "支持2至8路 INNER/LEFT Join；需要配置输出行数上限");
            result.put("sort-records", "仅支持有界任务，排序后须直接连接输出或脚本输入");
        }
        return Collections.unmodifiableMap(result);
    }
}
