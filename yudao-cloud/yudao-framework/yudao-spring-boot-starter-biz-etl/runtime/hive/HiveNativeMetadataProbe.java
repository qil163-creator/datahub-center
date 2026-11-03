package cn.iocoder.yudao.framework.etl.hive;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.HiveNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverBundleSpec;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeHiveCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 仅用于独立本机验收：由真实 Hive 目录事实生成编译器 SQL，不访问平台业务记录。 */
public final class HiveNativeMetadataProbe {
    public static void main(String[] arguments) throws Exception {
        Path driver = Path.of(arguments[0]).toRealPath(); String url = arguments[1], schema = arguments[2]; Path output = Path.of(arguments[3]);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(driver)));
        var spec = new DriverBundleSpec() {
            public String getPath() { return driver.toString(); }
            public String getSha256() { return checksum; }
            public String getDriverClassName() { return ProductTypeEnum.HIVE.getDriveClassName(); }
            public String getBundleRoot() { return null; }
            public List<? extends Artifact> getArtifacts() { return List.of(); }
            public Lifecycle getLifecycle() { return Lifecycle.PROCESS; }
        };
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.HIVE, spec));
             var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers);
             var connection = runtime.openConnection(ProductTypeEnum.HIVE, null, url, "hive", "")) {
            var metadata = new LinkedHashMap<String, Map<String, String>>();
            for (String name : List.of("source", "target")) {
                var columns = new LinkedHashMap<String, String>();
                for (var column : HiveNativeCatalog.read(connection, schema, name, 30)) columns.put(column.getFieldName(), column.getMetadataEvidence().nativeType().value());
                metadata.put(name, columns);
            }
            var rejectedStorage = new LinkedHashMap<String, String>();
            for (String name : List.of("partitioned", "source_view")) {
                try {
                    HiveNativeCatalog.read(connection, schema, name, 30);
                    throw new IllegalStateException("不支持的 Hive 存储未被拒绝：" + name);
                } catch (java.sql.SQLFeatureNotSupportedException expected) {
                    rejectedStorage.put(name, expected.getSQLState());
                }
            }
            var sourceTable = new EtlNativeSqlCompiler.Table(List.of("spark_catalog", schema, "source"), metadata.get("source"), ProductTypeEnum.HIVE, schema, "source");
            var targetTable = new EtlNativeSqlCompiler.Table(List.of("spark_catalog", schema, "target"), metadata.get("target"), ProductTypeEnum.HIVE, schema, "target");
            var source = EtlNativeHiveCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, schema + ":read", "read-hive",
                    Map.of("databaseId", 1, "tableName", "source", "schemaName", schema, "whereCondition", "id >= 2 AND name <> 'skip'",
                            "columns", List.of(Map.of("name", "id", "type", "LONG"), Map.of("name", "name", "type", "STRING", "alias", "label"),
                                    Map.of("name", "amount", "type", "DECIMAL", "length", 20, "precision", 3))), sourceTable);
            var target = EtlNativeHiveCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, schema + ":write", "write-hive",
                    Map.of("databaseId", 2, "tableName", "target", "schemaName", schema, "writeModeType", 2,
                            "columns", List.of(Map.of("name", "id", "type", "LONG"), Map.of("name", "name", "type", "STRING", "key", "label"), Map.of("name", "amount", "type", "DECIMAL"))), targetTable);
            var read = node("read", "read-db", source.nodeConfig()); var write = node("write", "insert-db", target.nodeConfig());
            var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write");
            String sql = source.initializationSql() + EtlNativeSqlCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, List.of(read, write), List.of(edge),
                    (database, table, namespace) -> table.equals(source.table().physicalTable()) ? source.table() : target.table());
            Files.createDirectories(output);
            Files.writeString(output.resolve("compiled.sql"), sql);
            var result = new LinkedHashMap<String, Object>(); result.put("serverVersion", HiveNativeCatalog.VERSION); result.put("metadata", metadata);
            result.put("rejectedStorage", rejectedStorage);
            result.put("compiledSqlSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            Files.writeString(output.resolve("metadata.json"), JSONUtil.toJsonStr(result));
            System.out.println("Hive native metadata and compiler SQL verified");
        }
    }
    private static EtlNodeDef node(String no, String code, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(no); node.setName(no); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
}
