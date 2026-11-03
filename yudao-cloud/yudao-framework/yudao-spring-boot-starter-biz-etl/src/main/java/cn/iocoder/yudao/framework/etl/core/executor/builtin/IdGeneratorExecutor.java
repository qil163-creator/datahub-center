package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ID 生成器节点（toolCode: id-generator）：为每行数据生成序列号或 UUID
 *
 * 配置：targetField 序列字段名称；mode SEQUENCE 增加序列 | UUID 增加UUID；
 * SEQUENCE 时：start 起始值（默认 1）、step 递增幅度（默认 1）、max 最大值（0 不限，超过报错）
 *
 * @author Li
 */
public class IdGeneratorExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "id-generator";

    /**
     * 初始化 {@code IdGeneratorExecutor}及其父类状态。
     */
    public IdGeneratorExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String targetField = requireConfig("targetField", "序列字段名称");
        boolean uuidMode = "UUID".equalsIgnoreCase(config().getStr("mode", "SEQUENCE"));
        long current = config().getLong("start", 1L);
        long step = config().getLong("step", 1L);
        long max = config().getLong("max", 0L);

        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Map<String, Object> result = new LinkedHashMap<>(row);
            if (uuidMode) {
                result.put(targetField, UUID.randomUUID().toString());
            } else {
                if (max > 0 && current > max) {
                    throw new IllegalStateException(StrUtil.format(
                            "序列值 {} 已超过最大值 {}", current, max));
                }
                result.put(targetField, current);
                current += step;
            }
            pushRow(result);
        }
        logLine(String.format("ID 生成完成，输出 %s 行", writeCount.get()));
    }

}
