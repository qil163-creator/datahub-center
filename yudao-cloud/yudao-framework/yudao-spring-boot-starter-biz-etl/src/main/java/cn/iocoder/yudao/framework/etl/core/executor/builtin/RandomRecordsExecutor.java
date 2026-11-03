package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** 无上游的随机数据生成节点（toolCode: random-records）。 */
public class RandomRecordsExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "random-records";

    private static final String DEFAULT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /**
     * 初始化 {@code RandomRecordsExecutor}及其父类状态。
     */
    public RandomRecordsExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        int count = config().getInt("count", 1);
        if (count < 0 || count > 1_000_000) {
            throw new IllegalStateException("随机记录数必须在 0 到 1000000 之间");
        }
        JSONArray fields = config().getJSONArray("fields");
        if (fields == null || fields.isEmpty()) {
            throw new IllegalStateException(String.format("节点【%s】缺少随机字段定义", node.getName()));
        }
        Long seed = config().getLong("seed");
        Random random = seed == null ? new Random() : new Random(seed);
        for (int i = 0; i < count; i++) {
            checkStop();
            Map<String, Object> row = new LinkedHashMap<>();
            for (Object item : fields) {
                JSONObject field = (JSONObject) item;
                String name = field.getStr("name");
                if (StrUtil.isBlank(name)) {
                    continue;
                }
                row.put(name, randomValue(field, random));
            }
            pushRow(row);
        }
        logLine(String.format("随机记录生成完成，共 %s 行", writeCount.get()));
    }

    /**
     * 按字段类型和范围配置生成随机值。
     */
    private Object randomValue(JSONObject field, Random random) {
        String type = field.getStr("type", "INTEGER").toUpperCase();
        return switch (type) {
            case "LONG" -> randomLong(field, random);
            case "DOUBLE" -> randomDouble(field, random);
            case "BOOLEAN" -> random.nextBoolean();
            case "UUID" -> new UUID((random.nextLong() & 0xffffffffffff0fffL) | 0x4000L,
                    (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L).toString();
            case "STRING" -> randomString(field, random);
            case "INTEGER" -> {
                if (field.getLong("min", 0L) < Integer.MIN_VALUE || field.getLong("max", 100L) > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("随机整数范围超出 Integer 边界");
                }
                yield Math.toIntExact(randomLong(field, random));
            }
            default -> throw new IllegalArgumentException("不支持的随机字段类型");
        };
    }

    /**
     * 在已校验的长整数范围内生成随机值。
     */
    private long randomLong(JSONObject field, Random random) {
        long min = field.getLong("min", 0L);
        long max = field.getLong("max", 100L);
        if (max < min) {
            throw new IllegalStateException("随机字段 max 不能小于 min");
        }
        long bound = max - min + 1;
        if (bound > 0) {
            return min + random.nextLong(bound);
        }
        long candidate;
        do {
            checkStop();
            candidate = random.nextLong();
        } while (candidate < min || candidate > max);
        return candidate;
    }

    /**
     * 在已校验的有限浮点范围内生成随机值。
     */
    private double randomDouble(JSONObject field, Random random) {
        double min = field.getDouble("min", 0D);
        double max = field.getDouble("max", 1D);
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(max - min) || max < min) {
            throw new IllegalStateException("随机小数范围必须有限且 max 不小于 min");
        }
        return min + random.nextDouble() * (max - min);
    }

    /**
     * 按受限长度配置生成随机字符串。
     */
    private String randomString(JSONObject field, Random random) {
        int length = field.getInt("length", 12);
        if (length < 0 || length > 4096) {
            throw new IllegalArgumentException("随机字符串长度必须在 0 至 4096 之间");
        }
        String chars = StrUtil.blankToDefault(field.getStr("characters"), DEFAULT_CHARS);
        if (chars.isEmpty()) {
            throw new IllegalStateException("随机字符串字符集不能为空");
        }
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(chars.charAt(random.nextInt(chars.length())));
        }
        return result.toString();
    }
}
