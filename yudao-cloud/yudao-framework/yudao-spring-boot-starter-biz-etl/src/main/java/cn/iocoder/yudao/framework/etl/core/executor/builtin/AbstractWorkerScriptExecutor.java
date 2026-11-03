package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 共享生命周期：每个节点使用一个工作进程，在有界批次之间保留脚本状态。 */
abstract class AbstractWorkerScriptExecutor extends AbstractEtlNodeExecutor {

    private final EtlScriptRunner.Language language;

    /**
     * 初始化 {@code AbstractWorkerScriptExecutor}及其父类状态。
     */
    protected AbstractWorkerScriptExecutor(EtlNodeDef node, EtlRunContext context, EtlScriptRunner.Language language) {
        super(node, context);
        this.language = language;
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected final void execute() throws Exception {
        for (String key : List.of("command", "classpath", "classpathEntries", "javaHome", "environmentVariables",
                "workingDirectory", "heapMegabytes", "jvmArguments")) {
            if (config().containsKey(key)) {
                throw new IllegalStateException("脚本节点不能指定 worker 启动配置");
            }
        }
        var runner = context.getScriptRunner();
        int batchSize = config().getInt("batchSize", 1000);
        if (batchSize < 1 || batchSize > runner.policy().maxBatchRows()) {
            throw new IllegalStateException("脚本批次行数超出 worker 上限");
        }
        logLine("启动受控脚本 worker，脚本输出内容不写入节点日志");
        try (var worker = runner.open(language, requireConfig("code", "脚本代码"), this::checkStop,
                context::markTerminationUnconfirmed)) {
            List<Map<String, Object>> batch = new ArrayList<>(batchSize);
            long estimatedBytes = 0;
            Map<String, Object> row;
            while ((row = readNextRow()) != null) {
                long bytes = EtlRunContext.estimateRowBytes(row);
                if (bytes > runner.policy().maxBatchBytes()) {
                    throw new IllegalStateException("脚本单行数据超过 worker 批次上限");
                }
                if (!batch.isEmpty() && (batch.size() >= batchSize || bytes > runner.policy()
                        .maxBatchBytes() - estimatedBytes)) {
                    output(worker.execute(batch));
                    batch = new ArrayList<>(batchSize);
                    estimatedBytes = 0;
                }
                batch.add(row);
                estimatedBytes += bytes;
            }
            if (!batch.isEmpty()) {
                output(worker.execute(batch));
            }
        }
        logLine(String.format("脚本 worker 执行完成，输出 %s 行", writeCount.get()));
    }

    /**
     * 返回输出。
     */
    private void output(List<Map<String, Object>> rows) throws Exception {
        for (var row : rows) {
            checkStop();
            pushRow(row);
        }
    }
}
