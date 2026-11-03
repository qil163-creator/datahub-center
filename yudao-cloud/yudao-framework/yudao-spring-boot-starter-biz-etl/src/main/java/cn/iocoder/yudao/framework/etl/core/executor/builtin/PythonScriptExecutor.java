package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Python 脚本节点（toolCode: python-script）
 *
 * 配置：script 脚本内容；interpreter 解释器（默认 python）；
 * workingDirectory / environmentVariables 同 Shell 节点。
 * 脚本写入临时 .py 文件后执行，避免命令行长度与转义问题
 *
 * @author Li
 */
public class PythonScriptExecutor extends ShellScriptExecutor {

    public static final String PY_TOOL_CODE = "python-script";

    /**
     * 初始化 {@code PythonScriptExecutor}及其父类状态。
     */
    public PythonScriptExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String script = requireConfig("script", "脚本内容");
        String interpreter = StrUtil.blankToDefault(config().getStr("interpreter"), "python");
        File scriptFile = File.createTempFile("etl-python-", ".py");
        try {
            FileUtil.writeString(script, scriptFile, StandardCharsets.UTF_8);
            runProcess(List.of(interpreter, scriptFile.getAbsolutePath()));
        } finally {
            FileUtil.del(scriptFile);
        }
    }

}
