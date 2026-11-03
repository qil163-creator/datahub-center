package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Map;

/** JSON 文件输出节点（toolCode: write-json）。支持数组和 NDJSON 两种输出。 */
public class WriteJsonExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "write-json";

    /**
     * 初始化 {@code WriteJsonExecutor}及其父类状态。
     */
    public WriteJsonExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String filePath = requireConfig("filePath", "输出目录");
        String fileName = requireConfig("fileName", "文件名");
        if (!fileName.toLowerCase().endsWith(".json") && !fileName.toLowerCase().endsWith(".jsonl")) {
            fileName += "NDJSON".equalsIgnoreCase(config().getStr("format", "ARRAY")) ? ".jsonl" : ".json";
        }
        Charset charset = Charset.forName(cn.hutool.core.util.StrUtil.blankToDefault(
                config().getStr("charset"), "UTF-8"));
        boolean ndjson = "NDJSON".equalsIgnoreCase(config().getStr("format", "ARRAY"));
        context.getFileOutputs().stage(node.getNo(), filePath, fileName);
        try (OutputStream output = context.getFileOutputs().open(node.getNo());
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, charset.newEncoder()))) {
            if (!ndjson) {
                writer.write("[");
            }
            boolean first = true;
            Map<String, Object> row;
            while ((row = readNextRow()) != null) {
                if (ndjson) {
                    writer.write(cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.write(row));
                    writer.newLine();
                } else {
                    if (!first) {
                        writer.write(",");
                    }
                    writer.write(cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.write(row));
                    first = false;
                }
                writeCount.incrementAndGet();
            }
            if (!ndjson) {
                writer.write("]");
            }
        }
        context.getFileOutputs().ready(node.getNo());
        logLine(String.format("JSON 临时文件写入完成，共 %s 行，等待流程成功后发布", writeCount.get()));
    }
}
