package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner;

/**
 * Java execute(rows) 编译与执行只在固定子 JVM 中进行，保留逐批实例状态。
 *
 * 配置：code 脚本源码（需实现 EtlJavaScript 接口）；batchSize 批次行数（默认 1000）。
 * 脚本只可使用受控工作进程类路径中的依赖；需要完整 JDK 和管理员启用。
 *
 * @author Li
 */
public class JavaScriptExecutor extends AbstractWorkerScriptExecutor {

    public static final String TOOL_CODE = "java-script";

    /**
     * 初始化 {@code JavaScriptExecutor}及其父类状态。
     */
    public JavaScriptExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context, EtlScriptRunner.Language.JAVA);
    }

}
