package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取 CSV 文件节点（toolCode: read-csv）
 *
 * 配置：fileUrl 文件地址（http/https 下载到临时文件）；charset 编码（默认 UTF-8）；
 * delimiter 分隔符（","、";"、"|"、"TAB"，默认逗号）；hasHeader 首行为表头（默认 true，
 * 否则按 A/B/C... 生成列名）；skipEmptyRows 跳过空行（默认 false）；
 * valueEncoding 默认 RAW；仅显式 DBSWITCH_CSV_V1 解码本组件的空值/公式保护转义。
 *
 * @author Li
 */
public class ReadCsvExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "read-csv";

    /**
     * 初始化 {@code ReadCsvExecutor}及其父类状态。
     */
    public ReadCsvExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String fileUrl = requireConfig("fileUrl", "文件地址");
        String charset = StrUtil.blankToDefault(config().getStr("charset"), "UTF-8");
        char delimiter = resolveDelimiter(config().getStr("delimiter"));
        boolean hasHeader = config().getBool("hasHeader", true);
        boolean skipEmptyRows = config().getBool("skipEmptyRows", false);
        String encoding = config().getStr("valueEncoding", "RAW");
        if (!List.of("RAW", "DBSWITCH_CSV_V1").contains(encoding)) {
            throw new IllegalArgumentException("CSV 值编码版本不支持");
        }
        boolean typedValues = "DBSWITCH_CSV_V1".equals(encoding);
        long maxBytes = context.getLimits() == null ? 64L * 1024 * 1024 : context.getLimits().getMaxBytes();

        var limits = new cn.iocoder.yudao.framework.etl.core.file.TabularFileReader.Limits(maxBytes,
                (int) Math.min(Integer.MAX_VALUE,
                        context.getLimits() == null ? 100_000 : context.getLimits().getMaxRows()),
                16_384, 1024 * 1024);
        var options = new cn.iocoder.yudao.framework.etl.core.file.TabularFileReader.CsvOptions(
                CharsetUtil.charset(charset), delimiter, hasHeader, skipEmptyRows, typedValues);
        File tempFile = File.createTempFile("etl-read-csv-", ".csv");
        try {
            logLine("下载 CSV 文件...");
            cn.iocoder.yudao.framework.etl.core.executor.support.EtlFileInput.download(fileUrl, tempFile.toPath(),
                    maxBytes);
            boolean gzip = "read-gzip-csv".equals(node.getToolCode())
                    || config().getBool("gzip", false) || fileUrl.toLowerCase().endsWith(".gz");
            try (InputStream raw = Files.newInputStream(tempFile.toPath());
                 InputStream input = gzip ? new GZIPInputStream(raw) : raw) {
                cn.iocoder.yudao.framework.etl.core.file.TabularFileReader.readCsv(input, options, limits,
                        (headers, values) -> {
                            checkStop();
                            Map<String, Object> rowData = new LinkedHashMap<>();
                            for (int i = 0; i < headers.size(); i++) {
                                rowData.put(headers.get(i), values.get(i));
                            }
                            readCount.incrementAndGet();
                            pushRow(rowData);
                        });
            }
        } finally {
            FileUtil.del(tempFile);
        }
        logLine(String.format("CSV 读取完成，共 %s 行", readCount.get()));
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
