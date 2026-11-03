package cn.iocoder.yudao.framework.etl.spark;

import cn.iocoder.yudao.framework.etl.core.file.EtlBoundedExcelReader;
import cn.iocoder.yudao.framework.etl.core.file.EtlManagedExcelInput;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;

import java.nio.file.*;
import java.util.*;

/** 官方 Hive UDTF 将一个已授权 Excel 文件完整展开为逐行文本数组，类型转换由明确的 Spark 视图完成。 */
public final class EtlReadExcelFunction extends GenericUDTF {
    private PrimitiveObjectInspector[] inspectors;

    @Override public StructObjectInspector initialize(ObjectInspector[] arguments) throws UDFArgumentException {
        if (arguments.length != 10) throw new UDFArgumentException("Excel 输入需要完整的10项读取参数");
        inspectors = new PrimitiveObjectInspector[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            if (!(arguments[index] instanceof PrimitiveObjectInspector inspector)) throw new UDFArgumentException("Excel 输入只接受标量读取参数");
            inspectors[index] = inspector;
        }
        return ObjectInspectorFactory.getStandardStructObjectInspector(List.of("cells"),
                List.of(ObjectInspectorFactory.getStandardListObjectInspector(PrimitiveObjectInspectorFactory.javaStringObjectInspector)));
    }

    @Override public void process(Object[] arguments) throws HiveException {
        try {
            String rootsFile = System.getenv("DATAHUB_ETL_FILE_ROOTS_FILE");
            Path configuration = Path.of(rootsFile == null || rootsFile.isBlank() ? "/opt/datahub-etl-excel/roots.txt" : rootsFile);
            if (!Files.isRegularFile(configuration, LinkOption.NOFOLLOW_LINKS) || Files.size(configuration) > 65536)
                throw new IllegalArgumentException("工作节点未配置 Excel 文件根目录授权");
            var roots = new HashSet<Path>();
            for (String value : Files.readAllLines(configuration)) if (!value.isBlank()) roots.add(Path.of(value).toAbsolutePath().normalize());
            String formulas = value(arguments, 9);
            if (!Set.of("LITERAL", "CACHED").contains(formulas)) throw new IllegalArgumentException("Excel 公式模式无效");
            String emptyRows = value(arguments, 8);
            if (!Set.of("true", "false").contains(emptyRows)) throw new IllegalArgumentException("Excel 空行选项无效");
            boolean skipEmpty = Boolean.parseBoolean(emptyRows);
            var options = new EtlBoundedExcelReader.Options(value(arguments, 2), number(arguments, 3), number(arguments, 4),
                    number(arguments, 5), skipEmpty, "CACHED".equals(formulas));
            EtlManagedExcelInput.read(roots, value(arguments, 0), value(arguments, 1), options,
                    number(arguments, 6), number(arguments, 7), EtlBoundedExcelReader.Limits.defaults(),
                    (headers, row) -> forward(new Object[]{row}));
        } catch (Exception invalid) {
            throw new HiveException("受管 Excel 读取失败：" + (invalid instanceof IllegalArgumentException ? invalid.getMessage() : "文件读取、路径授权或内容校验失败"));
        }
    }
    private String value(Object[] arguments, int index) {
        Object value = inspectors[index].getPrimitiveJavaObject(arguments[index]);
        if (value == null) throw new IllegalArgumentException("Excel 读取参数不能为空");
        return value.toString();
    }
    private int number(Object[] arguments, int index) { return Integer.parseInt(value(arguments, index)); }
    @Override public void close() { }
}
