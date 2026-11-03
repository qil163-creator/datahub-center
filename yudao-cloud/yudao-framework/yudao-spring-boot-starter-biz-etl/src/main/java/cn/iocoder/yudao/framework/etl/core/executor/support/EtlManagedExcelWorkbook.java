package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputs;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlTemporaryResource;
import org.apache.poi.openxml4j.util.ZipFileZipEntrySource;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.streaming.SheetDataWriter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 仅使用 POI 4.1.2 的公共扩展点：工作表 XML 和模板 ZIP 都登记为临时对象。 */
public final class EtlManagedExcelWorkbook extends SXSSFWorkbook {

    private final EtlFileOutputs outputs;

    private final String node;

    private final List<SheetDataWriter> writers = new ArrayList<>();

    private final List<EtlTemporaryResource> resources = new ArrayList<>();

    private boolean closed, written;

    private IOException closeFailure;

    /**
     * 初始化 {@code EtlManagedExcelWorkbook}及其父类状态。
     */
    public EtlManagedExcelWorkbook(EtlFileOutputs outputs, String node) {
        super(500);
        this.outputs = outputs;
        this.node = node;
    }

    /**
     * 创建工作表数据写入器。
     */
    @Override
    protected SheetDataWriter createSheetDataWriter() throws IOException {
        if (!writers.isEmpty() || closed) {
            throw new IOException("Excel output supports one managed worksheet");
        }
        var resource = outputs.temporary(node, "excel-sheet-1");
        resources.add(resource);
        // 构造器会调用这些公开重写方法。在构造 SheetDataWriter 前已捕获资源。
        var writer = new SheetDataWriter(getSharedStringSource()) {
            private Writer managedWriter;

            private boolean writerClosed;

            private IOException failure;

            /**
             * 返回当前已登记临时资源所对应的文件对象。
             */
            @Override
            public File createTempFile() throws IOException {
                return new NonDeletingFile(resource.registeredPath().toString());
            }

            /**
             * 创建写入器。
             */
            @Override
            public Writer createWriter(File ignored) throws IOException {
                managedWriter = new BufferedWriter(new OutputStreamWriter(resource.open(), StandardCharsets.UTF_8));
                return managedWriter;
            }

            /**
             * 返回工作表XML输入流。
             */
            @Override
            public InputStream getWorksheetXMLInputStream() throws IOException {
                return resource.openInput();
            }

            /**
             * 关闭当前工作簿及其文件资源。
             */
            @Override
            public void close() throws IOException {
                if (!writerClosed) {
                    writerClosed = true;
                    try {
                        managedWriter.flush();
                    } catch (IOException ex) {
                        failure = ex;
                    }
                    try {
                        managedWriter.close();
                    } catch (IOException ex) {
                        failure = append(failure, ex);
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        };
        writers.add(writer);
        return writer;
    }

    /** POI 默认写入会创建全局 poi-sxssf-template 文件；改为在本方清单下组合其公共 ZIP API。 */
    @Override
    public void write(OutputStream output) throws IOException {
        if (closed || written) {
            throw new IOException("Excel workbook is closed or already written");
        }
        written = true;
        flushSheets();
        for (var writer : writers) {
            writer.close();
        }
        var template = outputs.temporary(node, "excel-template-1");
        resources.add(template);
        try (var stream = template.open()) {
            getXSSFWorkbook().write(stream);
        }
        try (var zip = new ZipSecureFile(template.pathForRead().toFile()); var source = new ZipFileZipEntrySource(
                zip)) {
            injectData(source, output);
        }
    }

    /** POI 的 close 会吞掉工作表异常。必须传播异常，并在 READY 前确认每份清理回执。 */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            for (var writer : writers) {
                try {
                    writer.close();
                } catch (IOException ex) {
                    closeFailure = append(closeFailure, ex);
                }
            }
            try {
                getXSSFWorkbook().close();
            } catch (IOException ex) {
                closeFailure = append(closeFailure, ex);
            }
            for (var resource : resources.reversed()) {
                try {
                    resource.close();
                } catch (IOException ex) {
                    closeFailure = append(closeFailure, ex);
                }
            }
        }
        if (closeFailure != null) {
            throw new IOException("Excel temporary resources did not close successfully", closeFailure);
        }
    }

    /**
     * 尝试关闭当前工作簿并返回资源释放结果。
     */
    @Override
    public boolean dispose() {
        try {
            close();
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    /** SheetDataWriter 的包级私有 dispose/finalize 绝不能绕过基于身份的清理。 */
    private static final class NonDeletingFile extends File {

        /**
         * 初始化 {@code NonDeletingFile}及其父类状态。
         */
        private NonDeletingFile(String path) {
            super(path);
        }

        /**
         * 删除当前受管文件，或由包装器保留交由外部生命周期清理。
         */
        @Override
        public boolean delete() {
            return false;
        }

        /**
         * 拒绝当前实现未支持的 {@code deleteOnExit} 操作。
         */
        @Override
        public void deleteOnExit() {
            throw new UnsupportedOperationException("Managed temporary resources require a cleanup receipt");
        }
    }

    /**
     * 将给定内容追加到当前缓冲区，并更新累计大小。
     */
    private static IOException append(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }
}
