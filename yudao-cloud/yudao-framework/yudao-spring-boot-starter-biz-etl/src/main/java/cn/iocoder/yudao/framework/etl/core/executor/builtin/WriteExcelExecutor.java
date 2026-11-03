package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlExcelValues;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedExcelWorkbook;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 输出 Excel 文件节点（toolCode: write-excel）
 *
 * 配置：filePath 服务器目录；fileName 文件名（.xlsx）；sheetName 工作表名；
 * includeHeader 是否写标题行。使用 SXSSF 流式写出，列集合以首行数据为准
 *
 * @author Li
 */
public class WriteExcelExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "write-excel";

    /**
     * 初始化 {@code WriteExcelExecutor}及其父类状态。
     */
    public WriteExcelExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String filePath = requireConfig("filePath", "输出目录");
        String fileName = requireConfig("fileName", "文件名");
        if (!fileName.toLowerCase().endsWith(".xlsx")) {
            fileName = fileName + ".xlsx";
        }
        String sheetName = StrUtil.blankToDefault(config().getStr("sheetName"), "Sheet1");
        boolean includeHeader = config().getBool("includeHeader", true);

        context.getFileOutputs().stage(node.getNo(), filePath, fileName);

        try (var workbook = new EtlManagedExcelWorkbook(context.getFileOutputs(), node.getNo())) {
            Sheet sheet = workbook.createSheet(sheetName);
            List<String> headers = null;
            int rowIndex = 0;
            Map<String, Object> row;
            while ((row = readNextRow()) != null) {
                if (headers == null) {
                    headers = new ArrayList<>(row.keySet());
                    if (headers.isEmpty() || headers.stream().anyMatch(header -> header == null || header.isEmpty())) {
                        throw new IllegalArgumentException("Excel 输出字段名不能为空");
                    }
                    if (includeHeader) {
                        Row headerRow = sheet.createRow(rowIndex++);
                        for (int i = 0; i < headers.size(); i++) {
                            headerRow.createCell(i).setCellValue(headers.get(i));
                        }
                    }
                }
                if (!row.keySet().equals(new java.util.HashSet<>(headers))) {
                    throw new IllegalArgumentException("Excel 输出字段集合发生变化");
                }
                Row dataRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < headers.size(); i++) {
                    Object value = row.get(headers.get(i));
                    Cell cell = dataRow.createCell(i);
                    EtlExcelValues.write(cell, value);
                }
                writeCount.incrementAndGet();
            }
            try (OutputStream out = context.getFileOutputs().open(node.getNo())) {
                workbook.write(out);
            }
        }
        context.getFileOutputs().ready(node.getNo());
        logLine(String.format("Excel 临时文件写入完成，共 %s 行，等待流程成功后发布", writeCount.get()));
    }

}
