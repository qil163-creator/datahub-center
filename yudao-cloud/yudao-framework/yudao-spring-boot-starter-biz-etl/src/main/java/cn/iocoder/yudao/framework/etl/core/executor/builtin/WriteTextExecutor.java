package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Map;

/** 通用文本文件输出节点（toolCode: write-text）。template 使用 ${field} 占位。 */
public class WriteTextExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "write-text";

    /**
     * 初始化 {@code WriteTextExecutor}及其父类状态。
     */
    public WriteTextExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String filePath = requireConfig("filePath", "输出目录");
        String fileName = requireConfig("fileName", "文件名");
        String template = requireConfig("template", "行模板");
        String charsetName = StrUtil.blankToDefault(config().getStr("charset"), "UTF-8");
        String lineSeparator = config().getStr("lineSeparator", "\n");
        context.getFileOutputs().stage(node.getNo(), filePath, fileName);
        try (OutputStream output = context.getFileOutputs().open(node.getNo());
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(output, Charset.forName(charsetName).newEncoder()))) {
            Map<String, Object> row;
            while ((row = readNextRow()) != null) {
                String line = template;
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    line = line.replace("${" + entry.getKey() + "}",
                            entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                }
                writer.write(line);
                writer.write(lineSeparator);
                writeCount.incrementAndGet();
            }
        }
        context.getFileOutputs().ready(node.getNo());
        logLine(String.format("文本临时文件写入完成，共 %s 行，等待流程成功后发布", writeCount.get()));
    }
}
