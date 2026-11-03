package cn.iocoder.yudao.framework.etl.core.file;

import java.io.PushbackReader;
import java.io.Reader;
import java.util.*;

/** 有界分隔记录解析，保留引号状态以区分空字符串、NULL 标记和跨行字段。 */
public final class EtlDelimitedRows {
    private EtlDelimitedRows() { }
    public record Cell(String value, boolean quoted) { }
    @FunctionalInterface public interface Consumer { void accept(List<Cell> values) throws Exception; }

    public static void read(Reader source, char delimiter, char quote, char escape, boolean multiline,
                            int maxRecords, int maxColumns, int maxCellChars, Consumer consumer) throws Exception {
        if (maxRecords < 1 || maxColumns < 1 || maxCellChars < 1 || delimiter == '\r' || delimiter == '\n' || delimiter == quote && quote != 0)
            throw new IllegalArgumentException("分隔文件解析参数无效");
        var reader = new PushbackReader(source, 1);
        var fields = new ArrayList<Cell>(); var value = new StringBuilder();
        boolean quoted = false, inQuotes = false, afterQuote = false, started = false, record = false;
        int records = 0;
        for (int item; (item = reader.read()) >= 0;) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("文件读取已取消");
            char character = (char) item;
            if (inQuotes) {
                if (character == quote) {
                    int next = reader.read();
                    if (next == quote) value.append(quote);
                    else { inQuotes = false; afterQuote = true; if (next >= 0) reader.unread(next); }
                } else if (character == escape && escape != quote) {
                    int next = reader.read();
                    if (next == quote || next == escape) value.append((char) next);
                    else { value.append(character); if (next >= 0) reader.unread(next); }
                } else {
                    if (!multiline && (character == '\r' || character == '\n')) throw new IllegalArgumentException("当前文件未启用跨行字段");
                    value.append(character);
                }
            } else if (character == delimiter) {
                add(fields, value, quoted, maxColumns); value.setLength(0); quoted = false; afterQuote = false; started = false; record = true;
            } else if (character == '\r' || character == '\n') {
                if (character == '\r') { int next = reader.read(); if (next >= 0 && next != '\n') reader.unread(next); }
                add(fields, value, quoted, maxColumns);
                if (++records > maxRecords) throw new IllegalArgumentException("文件读取行数超过预算");
                consumer.accept(List.copyOf(fields)); fields.clear(); value.setLength(0);
                quoted = false; afterQuote = false; started = false; record = false;
            } else {
                if (afterQuote) throw new IllegalArgumentException("带引号字段结束后只允许分隔符或换行");
                if (quote != 0 && character == quote) {
                    if (started) throw new IllegalArgumentException("引号必须位于字段开头");
                    quoted = true; inQuotes = true;
                } else value.append(character);
                started = true; record = true;
            }
            if (value.length() > maxCellChars) throw new IllegalArgumentException("文件单元格超过字符预算");
        }
        if (inQuotes) throw new IllegalArgumentException("文件存在未闭合的引号字段");
        if (record || started || !fields.isEmpty()) {
            add(fields, value, quoted, maxColumns);
            if (++records > maxRecords) throw new IllegalArgumentException("文件读取行数超过预算");
            consumer.accept(List.copyOf(fields));
        }
    }
    private static void add(List<Cell> fields, StringBuilder value, boolean quoted, int maximum) {
        if (fields.size() >= maximum) throw new IllegalArgumentException("文件字段数超过预算");
        fields.add(new Cell(value.toString(), quoted));
    }
}
