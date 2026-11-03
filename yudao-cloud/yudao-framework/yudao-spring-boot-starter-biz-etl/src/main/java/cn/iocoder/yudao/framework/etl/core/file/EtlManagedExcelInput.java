package cn.iocoder.yudao.framework.etl.core.file;

import java.nio.file.Path;
import java.util.*;

/** 工作节点 Excel 输入复用受管普通文件边界，行列范围和公式语义由有界解析器执行。 */
public final class EtlManagedExcelInput {
    private EtlManagedExcelInput() { }

    public static void read(Set<Path> allowedRoots, String rootPath, String relativePath,
                            EtlBoundedExcelReader.Options options, int startColumn, int columnCount,
                            EtlBoundedExcelReader.Limits limits, EtlBoundedExcelReader.RowConsumer sink) throws Exception {
        if (startColumn < 1 || columnCount < 1 || (long) startColumn + columnCount - 1 > limits.maxColumns())
            throw new IllegalArgumentException("Excel 起始列或字段数量超出预算");
        String extension = relativePath == null ? "" : relativePath.substring(relativePath.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Set.of("xls", "xlsx").contains(extension)) throw new IllegalArgumentException("Excel 文件类型无效");
        EtlManagedLocalInput.read(allowedRoots, rootPath, relativePath, limits.maxBytes(), source ->
                EtlBoundedExcelReader.readExcel(source, extension, options, limits, (headers, values) -> {
                    if ((long) startColumn - 1 + columnCount > headers.size()) throw new IllegalArgumentException("Excel 显式字段超出实际源列范围");
                    sink.accept(new ArrayList<>(headers.subList(startColumn - 1, startColumn - 1 + columnCount)),
                            new ArrayList<>(values.subList(startColumn - 1, startColumn - 1 + columnCount)));
                }));
    }
}
