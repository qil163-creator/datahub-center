package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列拆分为多行节点（toolCode: split-to-rows）：把一个字段按分隔符拆开，每个值生成一行
 *
 * 配置：sourceField 需拆分的字段；delimiter 分隔符；targetField 目标字段（空则覆盖来源字段）；
 * skipEmpty 跳过空值（默认 true，来源为空时不生成行；false 时原样输出一行）
 *
 * @author Li
 */
public class SplitToRowsExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "split-to-rows";

    /**
     * 初始化 {@code SplitToRowsExecutor}及其父类状态。
     */
    public SplitToRowsExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String sourceField = requireConfig("sourceField", "需拆分的字段");
        String delimiter = requireConfig("delimiter", "分隔符");
        String targetField = StrUtil.blankToDefault(config().getStr("targetField"), sourceField);
        boolean skipEmpty = config().getBool("skipEmpty", true);

        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            Object value = row.get(sourceField);
            String text = value == null ? "" : String.valueOf(value);
            if (StrUtil.isEmpty(text)) {
                if (!skipEmpty) {
                    Map<String, Object> result = new LinkedHashMap<>(row);
                    result.put(targetField, null);
                    pushRow(result);
                }
                continue;
            }
            List<String> tokens = StrUtil.split(text, delimiter);
            for (String token : tokens) {
                if (skipEmpty && StrUtil.isBlank(token)) {
                    continue;
                }
                Map<String, Object> result = new LinkedHashMap<>(row);
                result.put(targetField, token);
                pushRow(result);
            }
        }
        logLine(String.format("列拆分为多行完成，输出 %s 行", writeCount.get()));
    }

}
