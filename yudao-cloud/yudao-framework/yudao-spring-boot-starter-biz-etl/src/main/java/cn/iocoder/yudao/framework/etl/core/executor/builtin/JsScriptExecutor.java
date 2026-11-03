package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner;

/**
 * JavaScript execute(rows) 只在固定子 JVM 中运行，保留逐批状态。
 *
 * 配置：code 脚本源码（需定义 execute(rows) 函数，rows 为对象数组，返回值作为节点输出）；
 * batchSize 批次行数（默认 1000）。数据经 JSON 转换传入脚本，数值/日期以 JSON 语义为准
 *
 * @author Li
 */
public class JsScriptExecutor extends AbstractWorkerScriptExecutor {

    public static final String TOOL_CODE = "js-script";

    /**
     * 初始化 {@code JsScriptExecutor}及其父类状态。
     */
    public JsScriptExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context, EtlScriptRunner.Language.JS);
    }

}
