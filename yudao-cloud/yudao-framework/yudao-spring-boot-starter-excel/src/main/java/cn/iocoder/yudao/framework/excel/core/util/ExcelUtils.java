package cn.iocoder.yudao.framework.excel.core.util;

import cn.idev.excel.FastExcelFactory;
import cn.idev.excel.context.AnalysisContext;
import cn.idev.excel.converters.longconverter.LongStringConverter;
import cn.idev.excel.enums.ReadDefaultReturnEnum;
import cn.idev.excel.metadata.data.ReadCellData;
import cn.idev.excel.support.ExcelTypeEnum;
import cn.idev.excel.write.handler.SheetWriteHandler;
import cn.idev.excel.write.metadata.holder.WriteSheetHolder;
import cn.idev.excel.write.metadata.holder.WriteWorkbookHolder;
import cn.idev.excel.read.listener.ReadListener;
import cn.iocoder.yudao.framework.common.util.http.HttpUtils;
import cn.iocoder.yudao.framework.excel.core.handler.ColumnWidthMatchStyleStrategy;
import cn.iocoder.yudao.framework.excel.core.handler.SelectSheetWriteHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Excel 工具类
 *
 * @author 芋道源码
 */
public class ExcelUtils {

    /**
     * 将列表以 Excel 响应给前端
     *
     * @param response  响应
     * @param filename  文件名
     * @param sheetName Excel sheet 名
     * @param head      Excel head 头
     * @param data      数据列表哦
     * @param <T>       泛型，保证 head 和 data 类型的一致性
     * @throws IOException 写入失败的情况
     */
    public static <T> void write(HttpServletResponse response, String filename, String sheetName,
                                 Class<T> head, List<T> data) throws IOException {
        prepareDownload(response, filename);
        // 输出 Excel
        FastExcelFactory.write(response.getOutputStream(), head)
                .autoCloseStream(false) // 不要自动关闭，交给 Servlet 自己处理
                .registerWriteHandler(new ColumnWidthMatchStyleStrategy()) // 基于 column 长度，自动适配。最大 255 宽度
                .registerWriteHandler(new SelectSheetWriteHandler(head)) // 基于固定 sheet 实现下拉框
                .registerConverter(new LongStringConverter()) // 避免 Long 类型丢失精度
                .sheet(sheetName).doWrite(data);
    }

    /**
     * 将动态表头和数据以 Excel 响应给前端
     *
     * @param response 响应
     * @param filename 文件名
     * @param sheetName Excel sheet 名
     * @param head Excel head 头
     * @param data 数据列表
     * @throws IOException 写入失败的情况
     */
    public static void write(HttpServletResponse response, String filename, String sheetName,
                             List<List<String>> head, List<List<Object>> data) throws IOException {
        prepareDownload(response, filename);
        // 输出 Excel
        FastExcelFactory.write(response.getOutputStream())
                .autoCloseStream(false) // 不要自动关闭，交给 Servlet 自己处理
                .registerWriteHandler(new ColumnWidthMatchStyleStrategy()) // 基于 column 长度，自动适配。最大 255 宽度
                .registerConverter(new LongStringConverter()) // 避免 Long 类型丢失精度
                .head(head).sheet(sheetName).doWrite(data);
    }

    /** 生成动态文本模板，避免 Excel 在录入编号和精确数值时自动转换格式。 */
    public static void writeTextTemplate(HttpServletResponse response, String filename, String sheetName,
            List<String> fields) throws IOException {
        prepareDownload(response, filename);
        FastExcelFactory.write(response.getOutputStream()).autoCloseStream(false)
                .head(fields.stream().map(List::of).toList())
                .registerWriteHandler(new SheetWriteHandler() {
                    @Override
                    public void afterSheetCreate(WriteWorkbookHolder workbook, WriteSheetHolder sheet) {
                        var style = workbook.getWorkbook().createCellStyle();
                        style.setDataFormat(workbook.getWorkbook().createDataFormat().getFormat("@"));
                        for (int i = 0; i < fields.size(); i++) {
                            sheet.getSheet().setDefaultColumnStyle(i, style);
                            sheet.getSheet().setColumnWidth(i, Math.min(255, Math.max(fields.get(i).length() + 3, 18)) * 256);
                        }
                    }
                }).sheet(sheetName).doWrite(List.of());
    }

    private static void prepareDownload(HttpServletResponse response, String filename) {
        // 写入工作簿时可能提交响应，必须提前发送实际 XLSX 格式及文件名。
        response.setHeader("Content-Disposition", "attachment;filename=" + HttpUtils.encodeUtf8(filename));
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    public static <T> List<T> read(MultipartFile file, Class<T> head) throws IOException {
        if (file == null || file.isEmpty()) {
            return Collections.emptyList();
        }
        // 参考 https://t.zsxq.com/zM77F 帖子，增加 try 处理，兼容 windows 场景
        try (InputStream inputStream = file.getInputStream()) {
            return FastExcelFactory.read(inputStream, head, null)
                    .autoCloseStream(false) // 不要自动关闭，交给 Servlet 自己处理
                    .doReadAllSync();
        }
    }

    /**
     * 读取指定数量以内的 Excel 数据，达到上限后停止继续解析。
     *
     * @param file Excel 文件，可为空
     * @param head Excel 表头类型
     * @param maxRowCount 最大读取行数
     * @return Excel 数据
     */
    public static <T> List<T> read(MultipartFile file, Class<T> head, int maxRowCount) throws IOException {
        if (file == null || file.isEmpty()) {
            return Collections.emptyList();
        }
        if (maxRowCount <= 0) {
            throw new IllegalArgumentException("maxRowCount must be greater than 0");
        }
        List<T> rows = new ArrayList<>(Math.min(maxRowCount, 1024));
        try (InputStream inputStream = file.getInputStream()) {
            FastExcelFactory.read(inputStream, head, new ReadListener<T>() {

                @Override
                public void invoke(T data, AnalysisContext context) {
                    rows.add(data);
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    // 无需处理
                }

                @Override
                public boolean hasNext(AnalysisContext context) {
                    return rows.size() < maxRowCount;
                }

            }).autoCloseStream(false).doReadAll();
        }
        return rows;
    }

    /**
     * 读取动态表头 Excel 数据
     *
     * @param file Excel 文件，可为空
     * @return 按列下标存储的行数据
     * @throws IOException 读取失败
     */
    public static List<Map<Integer, String>> read(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return Collections.emptyList();
        }
        // 参考 https://t.zsxq.com/zM77F 帖子，增加 try 处理，兼容 windows 场景
        try (InputStream inputStream = file.getInputStream()) {
            return FastExcelFactory.read(inputStream)
                    .autoCloseStream(false) // 不要自动关闭，交给 Servlet 自己处理
                    .headRowNumber(1)
                    .sheet()
                    .doReadSync();
        }
    }

    /** 第一张工作表的原始行，保留单元格类型、精确数值、实际行号和日期基准。 */
    public record RawRow(int number, Map<Integer, ReadCellData<?>> cells, boolean date1904) {}

    /** 流式读取首张 XLSX 工作表，表头计入行数；多读一行可用于识别超出限额的工作簿。 */
    public static List<RawRow> readXlsxRows(MultipartFile file, int limit) throws IOException {
        if (limit < 1) {
            throw new IllegalArgumentException("读取行数必须大于零");
        }
        List<RawRow> rows = new ArrayList<>(Math.min(limit, 256));
        try (InputStream stream = file.getInputStream()) {
            FastExcelFactory.read(stream, new ReadListener<Map<Integer, ReadCellData<?>>>() {
                @Override
                public void invoke(Map<Integer, ReadCellData<?>> cells, AnalysisContext context) {
                    rows.add(new RawRow(context.readRowHolder().getRowIndex() + 1, cells,
                            Boolean.TRUE.equals(context.readWorkbookHolder().getGlobalConfiguration().getUse1904windowing())));
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) { }

                @Override
                public boolean hasNext(AnalysisContext context) {
                    return rows.size() < limit;
                }
            }).excelType(ExcelTypeEnum.XLSX).readDefaultReturn(ReadDefaultReturnEnum.READ_CELL_DATA)
                    .autoCloseStream(false).autoTrim(false).headRowNumber(0).sheet().doRead();
        }
        return rows;
    }
}
