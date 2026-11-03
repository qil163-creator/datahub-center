package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.io.FileUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlExcelValues;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取 Excel 文件节点（toolCode: read-excel）
 *
 * 配置：fileUrl 文件地址（http/https 下载到临时文件）；sheetName 工作表（空取第一个）；
 * headerRow 标题行号（1 基，0 表示无标题按 A/B/C 列名）；startRow 数据起始行（1 基）；
 * endRow 结束行（0 到文件末尾）；skipEmptyRows 跳过空行
 *
 * @author Li
 */
public class ReadExcelExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "read-excel";

    /**
     * 初始化 {@code ReadExcelExecutor}及其父类状态。
     */
    public ReadExcelExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String fileUrl = requireConfig("fileUrl", "文件地址");
        String sheetName = config().getStr("sheetName");
        int headerRow = config().getInt("headerRow", 1);
        int startRow = config().getInt("startRow", headerRow > 0 ? headerRow + 1 : 1);
        int endRow = config().getInt("endRow", 0);
        boolean skipEmptyRows = config().getBool("skipEmptyRows", false);
        if (headerRow < 0 || startRow < 1 || endRow < 0 || endRow > 0 && endRow < startRow) {
            throw new IllegalArgumentException("Excel 行范围无效");
        }
        var formulas = EtlExcelValues.FormulaMode.valueOf(config().getStr("formulaMode", "LITERAL"));
        long maxBytes = context.getLimits() == null ? 64L * 1024 * 1024 : context.getLimits().getMaxBytes();

        var limits = new cn.iocoder.yudao.framework.etl.core.file.TabularFileReader.Limits(maxBytes,
                (int) Math.min(Integer.MAX_VALUE,
                        context.getLimits() == null ? 100_000 : context.getLimits().getMaxRows()),
                16_384, 1024 * 1024);
        var options = new cn.iocoder.yudao.framework.etl.core.file.TabularFileReader.ExcelOptions(
                sheetName, headerRow, startRow, endRow, skipEmptyRows, formulas);
        File tempFile = File.createTempFile("etl-read-excel-", resolveSuffix(fileUrl));
        try {
            logLine("下载 Excel 文件...");
            cn.iocoder.yudao.framework.etl.core.executor.support.EtlFileInput.download(fileUrl, tempFile.toPath(),
                    maxBytes);
            try (InputStream input = new FileInputStream(tempFile)) {
                cn.iocoder.yudao.framework.etl.core.file.TabularFileReader.readExcel(input,
                        resolveSuffix(fileUrl).substring(1), options, limits, (headers, values) -> {
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
        logLine(String.format("Excel 读取完成，共 %s 行", readCount.get()));
    }

    /**
     * 解析后缀。
     */
    private String resolveSuffix(String fileUrl) {
        String path = fileUrl.split("\\?")[0].toLowerCase();
        return path.endsWith(".xls") ? ".xls" : ".xlsx";
    }

}
