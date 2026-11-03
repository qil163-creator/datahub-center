package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 有界持久化暂存：上游仍在运行时，此节点绝不提交目标数据。 */
public class ManagedDbOutputExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "managed-db-output";

    public static final int MAX_ROWS = 5_000;

    public static final long MAX_BYTES = 8L * 1024 * 1024;

    /**
     * 初始化 {@code ManagedDbOutputExecutor}及其父类状态。
     */
    public ManagedDbOutputExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        var output = context.getStagedOutputPort();
        if (output == null) {
            throw new IllegalStateException("受管输出需要持久提交适配器");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long bytes = 2;
        Map<String, Object> row;
        while ((row = readNextRow()) != null) {
            bytes = Math.addExact(bytes, JSONUtil.toJsonStr(row).getBytes(StandardCharsets.UTF_8).length + 1L);
            if (rows.size() >= MAX_ROWS || bytes > MAX_BYTES) {
                throw new IllegalStateException("受管目标批次超过 5000 行或 8 MiB，请缩小源批次");
            }
            rows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        checkStop();
        output.stage(node.getNo(), Collections.unmodifiableList(rows));
        writeCount.set(rows.size());
        logLine("目标数据已暂存，等待完整流程成功后提交");
    }
}
