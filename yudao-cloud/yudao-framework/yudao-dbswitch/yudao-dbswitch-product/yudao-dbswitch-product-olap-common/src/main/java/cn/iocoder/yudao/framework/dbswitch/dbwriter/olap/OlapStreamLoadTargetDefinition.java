package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.util.*;

/** 现有跨产品 CREATE 生成器的纯准入检查，先于 DROP 或 CREATE。
 * 同产品原生分区、索引传输使用独立的原生计划和运行时观测。
 * 此语法有意缩小：仅允许单个完整行表、原生标量列、单个键模型、
 * 哈希分布及副本数。拒绝未知默认值、函数、索引和后缀。
 */
public final class OlapStreamLoadTargetDefinition {

    /**
     * 初始化 {@code OlapStreamLoadTargetDefinition}。
     */
    private OlapStreamLoadTargetDefinition() {
    }

    /**
     * 检查数据库产品、模式、表是否满足当前操作的约定。
     */
    public static void require(String product, String schema, String table, List<String> statements,
            List<String> fields) {
        try {
            if (statements == null || statements.size() != 1 || fields == null || fields.isEmpty()
                    || fields.size() > 1024 || new HashSet<>(fields).size() != fields.size()) {
                throw invalid();
            }
            OlapStreamLoadProfile profile = OlapStreamLoadProfiles.of(product);
            String textOrBinaryKind = profile.targetTextOrBinaryKind(), keyedModel = profile.targetKeyedModel();
            var p = new Parser(statements.getFirst());
            p.word("CREATE");
            p.word("TABLE");
            if (p.takeWord("IF")) {
                p.word("NOT");
                p.word("EXISTS");
            }
            if (!schema.equals(p.identifier())) {
                throw invalid();
            }
            p.require(".");
            if (!table.equals(p.identifier())) {
                throw invalid();
            }
            p.require("(");
            var columns = new LinkedHashMap<String, Boolean>();
            var kinds = new HashMap<String, String>();
            do {
                String name = p.identifier(), type = p.bare().toLowerCase(Locale.ROOT);
                if (p.take("(")) {
                    type += "(" + p.number();
                    if (p.take(",")) {
                        type += "," + p.number();
                    }
                    p.require(")");
                    type += ")";
                }
                String kind = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
                if (!Set.of("boolean", "tinyint", "smallint", "int", "bigint", "largeint", "float", "double",
                        "decimal", "date", "datetime", "char", "varchar", textOrBinaryKind).contains(kind)) {
                    throw invalid();
                }
                profile.requireTargetNativeType(type);
                boolean nullable = true;
                if (p.takeWord("NOT")) {
                    p.word("NULL");
                    nullable = false;
                } else {
                    p.takeWord("NULL");
                }
                if (p.takeWord("COMMENT")) {
                    p.string();
                }
                if (columns.put(name, nullable) != null || columns.size() > 1024) {
                    throw invalid();
                }
                kinds.put(name, kind);
            } while (p.take(","));
            p.require(")");
            if (p.takeWord("ENGINE")) {
                p.require("=");
                p.word("OLAP");
            }
            String model = p.bare().toUpperCase(Locale.ROOT);
            if (!model.equals("DUPLICATE") && !model.equals(keyedModel)) {
                throw invalid();
            }
            p.word("KEY");
            List<String> keys = p.names();
            if (keys.size() > columns.size() || !new ArrayList<>(columns.keySet()).subList(0, keys.size())
                    .equals(keys)) {
                throw invalid();
            }
            // 现有生成器无法在替换目标前证明任意原生键值域。
            // 将 CREATE 路径限制为精确整数、本地日期、有界文本键；
            // 已准备的原生表保留独立观察到的更丰富键契约。
            if (keys.stream().anyMatch(key -> !Set.of("tinyint", "smallint", "int", "bigint", "largeint",
                    "date", "datetime", "char", "varchar").contains(kinds.get(key)))) {
                throw invalid();
            }
            if (!model.equals("DUPLICATE") && keys.stream().anyMatch(columns::get)) {
                throw invalid();
            }
            if (p.takeWord("COMMENT")) {
                p.string();
            }
            p.word("DISTRIBUTED");
            p.word("BY");
            p.word("HASH");
            List<String> distribution = p.names();
            if (!columns.keySet().containsAll(distribution) || !model.equals("DUPLICATE") && !keys.containsAll(
                    distribution)) {
                throw invalid();
            }
            p.word("BUCKETS");
            int buckets = p.number();
            if (buckets < 1 || buckets > 65536) {
                throw invalid();
            }
            p.word("PROPERTIES");
            p.require("(");
            var properties = new HashMap<String, String>();
            do {
                String name = p.string();
                p.require("=");
                String value = p.string();
                if (properties.put(name, value) != null || !name.equals("replication_num") || !value.matches(
                        "[1-9][0-9]?")) {
                    throw invalid();
                }
            } while (p.take(","));
            p.require(")");
            p.take(";");
            if (!p.end() || !properties.containsKey("replication_num") || !columns.keySet()
                    .equals(new HashSet<>(fields))) {
                throw invalid();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("OLAP WRITE_COPY planned native target definition is not verified");
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Unsupported OLAP target definition");
    }

    private static final class Parser {

        private final String text;

        private int at;

        /**
         * 初始化 {@code Parser}。
         */
        Parser(String text) {
            if (text == null || text.length() > 1_048_576) {
                throw invalid();
            }
            this.text = text;
        }

        /**
         * 跳过当前解析位置的空白字符。
         */
        void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        /**
         * 返回当前终点。
         */
        boolean end() {
            space();
            return at == text.length();
        }

        /**
         * 消费当前解析位置要求的字符或词元。
         */
        boolean take(String token) {
            space();
            if (!text.startsWith(token, at)) {
                return false;
            }
            at += token.length();
            return true;
        }

        /**
         * 检查令牌是否满足当前操作的约定。
         */
        void require(String token) {
            if (!take(token)) {
                throw invalid();
            }
        }

        /**
         * 读取不带引号的标识符词元。
         */
        String bare() {
            space();
            int start = at;
            while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
                at++;
            }
            if (at == start) {
                throw invalid();
            }
            return text.substring(start, at);
        }

        /**
         * 尝试匹配并消费指定词元。
         */
        boolean takeWord(String word) {
            int save = at;
            try {
                if (bare().equalsIgnoreCase(word)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
            }
            at = save;
            return false;
        }

        /**
         * 按当前解析位置读取或匹配一个词元。
         */
        void word(String word) {
            if (!takeWord(word)) {
                throw invalid();
            }
        }

        /**
         * 按当前值域约定解析或转换数值。
         */
        int number() {
            String number = bare();
            if (!number.matches("[0-9]{1,8}")) {
                throw invalid();
            }
            return Integer.parseInt(number);
        }

        // 所有准入产品都使用 MySQL 协议的反引号标识符规则。

        /**
         * 检查或转换当前数据库标识符。
         */
        String identifier() {
            space();
            String value = at < text.length() && text.charAt(at) == '`' ? quoted('`') : bare();
            RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, value);
            return value;
        }

        /**
         * 按当前值域约定解析或转换字符串。
         */
        String string() {
            space();
            if (at == text.length() || text.charAt(at) != '\'' && text.charAt(at) != '"') {
                throw invalid();
            }
            return quoted(text.charAt(at));
        }

        /**
         * 返回按当前方言加引号后的标识符。
         */
        String quoted(char delimiter) {
            at++;
            var result = new StringBuilder();
            while (at < text.length()) {
                char value = text.charAt(at++);
                if (value == delimiter) {
                    if (at < text.length() && text.charAt(at) == delimiter) {
                        at++;
                        result.append(value);
                        continue;
                    }
                    return result.toString();
                }
                if (value == '\\') {
                    if (at == text.length()) {
                        throw invalid();
                    }
                    value = switch (text.charAt(at++)) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '\\' -> '\\';
                        case '\'' -> '\'';
                        case '"' -> '"';
                        case '`' -> '`';
                        default -> throw invalid();
                    };
                }
                if (value == 0 || result.length() >= 65536) {
                    throw invalid();
                }
                result.append(value);
            }
            throw invalid();
        }

        /**
         * 校验当前模式名与对象名符合原生标识符约定。
         */
        List<String> names() {
            require("(");
            var names = new ArrayList<String>();
            do {
                names.add(identifier());
                if (names.size() > 1024) {
                    throw invalid();
                }
            } while (take(","));
            require(")");
            if (new HashSet<>(names).size() != names.size()) {
                throw invalid();
            }
            return names;
        }
    }
}
