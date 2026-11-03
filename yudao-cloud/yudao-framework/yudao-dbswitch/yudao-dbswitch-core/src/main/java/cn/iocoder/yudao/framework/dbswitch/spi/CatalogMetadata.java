package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;

import java.util.List;

/** SDK 只读目录扩展；不授予 JDBC、SQL 执行、数据预览或 DDL 能力。 */
public interface CatalogMetadata {
    record Table(String name, String comment, Boolean view) { }

    /** 未由厂商目录声明的约束保留 null；分区列与普通列分别标记。 */
    record Column(String name, String nativeType, String comment, Boolean nullable,
                  Integer length, Integer precision, Integer scale, boolean partition) { }

    record Structure(Table table, List<Column> columns) {
        public Structure { columns = List.copyOf(columns); }
    }

    List<Table> tables(ConnectionProbeRequest request);

    Structure describe(ConnectionProbeRequest request, String table);
}
