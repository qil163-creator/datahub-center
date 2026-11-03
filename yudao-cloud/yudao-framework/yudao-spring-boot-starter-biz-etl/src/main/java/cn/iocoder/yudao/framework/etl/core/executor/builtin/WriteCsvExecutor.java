package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.text.csv.CsvWriteConfig;
import cn.hutool.core.text.csv.CsvWriter;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 输出 CSV 文件节点（toolCode: write-csv）
 *
 * 配置：filePath 服务器目录；fileName 文件名（.csv）；charset 编码（默认 UTF-8）；
 * delimiter 分隔符（","、";"、"|"、"TAB"，默认逗号）；includeHeader 是否写标题行（默认 true）。
 * 流式写出，列集合以首行数据为准；valueEncoding 固定 DBSWITCH_CSV_V1，回读须显式选择该版本。
 *
 * @author Li
 */
public class WriteCsvExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "write-csv";

    /**
     * 初始化 {@code WriteCsvExecutor}及其父类状态。
     */
    public WriteCsvExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String filePath = requireConfig("filePath", "输出目录");
        String fileName = requireConfig("fileName", "文件名");
        if (!fileName.toLowerCase().endsWith(".csv")) {
            fileName = fileName + ".csv";
        }
        String charset = StrUtil.blankToDefault(config().getStr("charset"), "UTF-8");
        char delimiter = resolveDelimiter(config().getStr("delimiter"));
        boolean includeHeader = config().getBool("includeHeader", true);
        boolean spreadsheetSafe = config().getBool("spreadsheetSafe", true);
        if (!"DBSWITCH_CSV_V1".equals(config().getStr("valueEncoding", "DBSWITCH_CSV_V1"))) {
            throw new IllegalArgumentException("CSV 输出值编码版本不支持");
        }

        context.getFileOutputs().stage(node.getNo(), filePath, fileName);

        CsvWriteConfig writeConfig = new CsvWriteConfig();
        writeConfig.setFieldSeparator(delimiter);
        try (OutputStream output = context.getFileOutputs().open(node.getNo());
             CsvWriter writer = new CsvWriter(new OutputStreamWriter(output, CharsetUtil.charset(charset)),
                     writeConfig)) {
            List<String> headers = null;
            Map<String, Object> row;
            while ((row = readNextRow()) != null) {
                if (headers == null) {
                    headers = new ArrayList<>(row.keySet());
                    if (includeHeader) {
                        writer.writeLine(headers.stream().map(header ->
                                        cn.iocoder.yudao.framework.etl.core.executor.support.EtlTextValues.encode(header,
                                                spreadsheetSafe))
                                .toArray(String[]::new));
                    }
                }
                if (!row.keySet().equals(new java.util.HashSet<>(headers))) {
                    throw new IllegalArgumentException("CSV 输出字段集合发生变化");
                }
                String[] values = new String[headers.size()];
                for (int i = 0; i < headers.size(); i++) {
                    Object value = row.get(headers.get(i));
                    values[i] = cn.iocoder.yudao.framework.etl.core.executor.support.EtlTextValues.encode(value,
                            spreadsheetSafe);
                }
                writer.writeLine(values);
                writeCount.incrementAndGet();
            }
            writer.flush();
        }
        context.getFileOutputs().ready(node.getNo());
        logLine(String.format("CSV 临时文件写入完成，共 %s 行，等待流程成功后发布", writeCount.get()));
    }

    /**
     * 解析分隔符。
     */
    private char resolveDelimiter(String delimiter) {
        if (StrUtil.isBlank(delimiter)) {
            return ',';
        }
        if ("TAB".equalsIgnoreCase(delimiter) || "\\t".equals(delimiter)) {
            return '\t';
        }
        return delimiter.charAt(0);
    }

}
