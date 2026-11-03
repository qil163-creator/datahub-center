package cn.iocoder.yudao.framework.dbswitch.common.type;

import java.util.Arrays;

/**
 * 数据库产品类型的枚举定义
 *
 * @author Tang
 */
public enum ProductTypeEnum {
    /**
     * 未知数据库类型
     */
    UNKNOWN(0, "\"", null, null, null),

    /**
     * MySQL数据库类型
     */
    MYSQL(1, "`", "com.mysql.cj.jdbc.Driver", "/* ping */ SELECT 1",
            "jdbc:mysql://{host}:{port}/{database}?useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=GMT%2B8&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"),

    /**
     * Oracle数据库类型
     */
    ORACLE(2, "\"", "oracle.jdbc.driver.OracleDriver", "SELECT 'Hello' from DUAL",
            "jdbc:oracle:thin:@{host}:{port}:{database}"),

    /**
     * SQLServer 2000数据库类型
     */
    SQLSERVER2000(3, "\"", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "SELECT 1 + 2 as a",
            "jdbc:sqlserver://{host}:{port};DatabaseName={database}"),

    /**
     * SQLServer数据库类型
     */
    SQLSERVER(4, "\"", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "SELECT 1+2 as a",
            "jdbc:sqlserver://{host}:{port};DatabaseName={database}"),

    /**
     * PostgreSQL数据库类型
     */
    POSTGRESQL(5, "\"", "org.postgresql.Driver", "SELECT 1", "jdbc:postgresql://{host}:{port}/{database}"),

    /**
     * Greenplum数据库类型（使用 PostgreSQL JDBC 驱动；
     * 历史配置的 com.pivotal.jdbc.GreenplumDriver 专有驱动不在依赖中，按枚举默认驱动建连会直接 ClassNotFound）
     */
    GREENPLUM(6, "\"", "org.postgresql.Driver", "SELECT 1", "jdbc:postgresql://{host}:{port}/{database}"),

    /**
     * MariaDB数据库类型
     */
    MARIADB(7, "`", "org.mariadb.jdbc.Driver", "SELECT 1", "jdbc:mariadb://{host}:{port}/{database}"),

    /**
     * DB2数据库类型
     */
    DB2(8, "\"", "com.ibm.db2.jcc.DB2Driver", "SELECT 1 FROM SYSIBM.SYSDUMMY1", "jdbc:db2://{host}:{port}/{database}"),

    /**
     * [国产]达梦数据库类型
     */
    DM(9, "\"", "dm.jdbc.driver.DmDriver", "SELECT 'Hello' from DUAL",
            "jdbc:dm://{host}:{port}/{database}?schema={database}&zeroDateTimeBehavior=convertToNull&useUnicode=true&characterEncoding=UTF-8"),

    /**
     * [国产]人大金仓数据库类型
     */
    KINGBASE(10, "\"", "com.kingbase8.Driver", "SELECT 1", "jdbc:kingbase8://{host}:{port}/{database}"),

    /**
     * [国产]神通数据库
     */
    OSCAR(11, "\"", "com.oscar.Driver", "SELECT 1", "jdbc:oscar://{host}:{port}/{database}"),

    /**
     * [国产]南大通用GBase8a数据库
     */
    GBASE8A(12, "`", "com.gbase.jdbc.Driver", "/* ping */ SELECT 1", "jdbc:gbase://{host}:{port}/{database}"),

    /**
     * HIVE数据库
     */
    HIVE(13, "`", "org.apache.hive.jdbc.HiveDriver", "SELECT 1", "jdbc:hive2://{host}:{port}/{database}"),

    /**
     * SQLite数据库（URL 模板使用可读写的文件模式；
     * 历史模板 jdbc:sqlite::resource:{file} 为只读资源模式，作为目标端必然写入失败）
     */
    SQLITE3(14, "\"", "org.sqlite.JDBC", "SELECT 1", "jdbc:sqlite:{file}"),

    /**
     * Sybase数据库类型
     */
    SYBASE(15, "\"", "com.sybase.jdbc4.jdbc.SybDriver", "SELECT 1+2 as a", "jdbc:sybase:Tds:{host}:{port}/{database}"),

    /**
     * DORIS
     */
    DORIS(16, "`", "com.mysql.cj.jdbc.Driver", "/* ping */ SELECT 1",
            "jdbc:mysql://{host}:{port}/{database}?useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=GMT%2B8&rewriteBatchedStatements=true"),

    /**
     * CLICKHOUSE
     */
    CLICKHOUSE(17, "`", "com.clickhouse.jdbc.ClickHouseDriver", "SELECT 1",
            "jdbc:clickhouse://{host}:{port}/{database}"),

    /**
     * MONGODB
     */
    MONGODB(18, "\"", "com.gitee.jdbc.mongodb.JdbcDriver", "db.runCommand({ping:1,maxTimeMS:5000})",
            "jdbc:mongodb://{host}:{port}/{database}?authSource=admin"),

    /**
     * TiDB
     */
    TIDB(19, "`", "com.mysql.cj.jdbc.Driver", "SELECT 1",
            "jdbc:mysql://{host}:{port}/{database}?characterEncoding=utf8&useSSL=false&useServerPrepStmts=true&prepStmtCacheSqlLimit=10000000000&useConfigs=maxPerformance&rewriteBatchedStatements=true&defaultfetchsize=-214783648&zeroDateTimeBehavior=CONVERT_TO_NULL"),
    /**
     * TDengine
     */
    TDENGINE(20, "`", "com.taosdata.jdbc.ws.WebSocketDriver", "SELECT 1", "jdbc:TAOS-WS://{host}:{port}/{database}"),
    /**
     * InfluxDB (Flight SQL / Arrow JDBC)
     */
    INFLUXDB3(21, "\"", "org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver", "SELECT 1",
            "jdbc:arrow-flight-sql://{host}:{port}/?useEncryption=0&database={database}"),

    /**
     * StarRocks
     */
    STARROCKS(22, "`", "com.mysql.cj.jdbc.Driver", "/* ping */ SELECT 1",
            "jdbc:mysql://{host}:{port}/{database}?useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull&useSSL=false&serverTimezone=GMT%2B8&rewriteBatchedStatements=true"),

    /**
     * [国产]瀚高数据库类型
     */
    HIGHGO(23, "\"", "com.highgo.jdbc.Driver", "SELECT 1", "jdbc:highgo://{host}:{port}/{database}"),

    /**
     * [国产]openGauss数据库类型
     */
    OPENGAUSS(24, "\"", "org.opengauss.Driver", "SELECT 1", "jdbc:opengauss://{host}:{port}/{database}"),

    /**
     * ElasticSearch数据库类型
     */
    ELASTICSEARCH(25, "\"", "com.gitee.jdbc.elasticsearch.JdbcDriver", "", "jdbc:jest://{host}:{port}"),

    /**
     * OceanBase数据库类型
     */
    OCEANBASE(26, "", "com.oceanbase.jdbc.Driver", "/* ping */",
            "jdbc:oceanbase://{host}:{port}/{database}?pool=false&useUnicode=true&characterEncoding=utf-8&useSSL=false"),

    /**
     * [国产]优炫数据库类型
     */
    UXDB(27, "\"", "com.uxsino.uxdb.Driver", "SELECT 1", "jdbc:uxdb://{host}:{port}/{database}"),
    /** 仅支持独立测连的南大通用 GBase 8c。 */
    GBASE8C(28, "\"", "com.gbase8c.Driver", "SELECT 1", "jdbc:gbase8c://{host}:{port}/{database}"),
    /** 华为 GaussDB DWS，使用 PostgreSQL 协议；不等同于其他 GaussDB 产品。 */
    GAUSSDB(29, "\"", "org.postgresql.Driver", "SELECT 1", "jdbc:postgresql://{host}:{port}/{database}"),
    /** Phoenix Query Server thin 协议。 */
    PHOENIX(30, "\"", "org.apache.phoenix.queryserver.client.Driver", "SELECT 1",
            "jdbc:phoenix:thin:url=http://{host}:{port};serialization=PROTOBUF"),
    /** SAP HANA 原生 JDBC。 */
    SAPHANA(31, "\"", "com.sap.db.jdbc.Driver", "SELECT 1 FROM DUMMY",
            "jdbc:sap://{host}:{port}/?databaseName={database}"),
    /** PrestoDB JDBC，不将 Trino 冒充为 Presto。 */
    PRESTO(32, "\"", "com.facebook.presto.jdbc.PrestoDriver", "SELECT 1", "jdbc:presto://{host}:{port}/{database}"),
    /** MaxCompute 原生项目授权探测。 */
    ODPS(33, "`", null, null, null),
    /** Neo4j Bolt 原生探测。 */
    NEO4J(34, "`", null, null, null),
    /** Milvus REST v2 原生探测。 */
    MILVUS(35, "\"", null, null, null),
    /** InfluxDB 2.x HTTP Token、组织和桶探测，与 Flight SQL 路径独立。 */
    INFLUXDB2(36, "\"", null, null, null),
    ;

    private Integer index;

    private String quote;

    private String driveClassName;

    private String testSql;

    private String url;

    /**
     * 返回探测SQL。
     */
    public String getTestSql() {
        return testSql;
    }

    /**
     * 返回地址。
     */
    public String getUrl() {
        return url;
    }

    /**
     * 返回驱动类名称。
     */
    public String getDriveClassName() {
        return driveClassName;
    }

    /**
     * 初始化 {@code ProductTypeEnum}。
     */
    ProductTypeEnum(Integer idx, String quote, String driveClassName, String testSql, String url) {
        this.index = idx;
        this.quote = quote;
        this.driveClassName = driveClassName;
        this.testSql = testSql;
        this.url = url;
    }

    /**
     * 返回索引。
     */
    public Integer getIndex() {
        return index;
    }


    /**
     * 返回依据索引。
     */
    public static ProductTypeEnum getByIndex(Integer index) {
        return Arrays.stream(ProductTypeEnum.values())
                .filter(productTypeEnum -> productTypeEnum.getIndex().equals(index)).findFirst()
                .orElse(ProductTypeEnum.UNKNOWN);
    }


    /**
     * 引用名称。
     */
    public String quoteName(String name) {
        return String.format("%s%s%s", quote, name, quote);
    }

    /**
     * 引用模式表名称。
     */
    public String quoteSchemaTableName(String schema, String table) {
        return String.format("%s%s%s.%s%s%s", quote, schema, quote, quote, table, quote);
    }

    /**
     * 类似于PostgreSQL系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikePostgres() {
        return this == POSTGRESQL || this == KINGBASE || this == GREENPLUM
                || this == HIGHGO || this == OPENGAUSS || this == UXDB;
    }

    /**
     * 类似于MySQL系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikeMysql() {
        return this == MYSQL || this == MARIADB || this == GBASE8A || this == DORIS || this == TIDB || this == STARROCKS;
    }

    /**
     * 类似于Oracle系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikeOracle() {
        return this == ORACLE || this == DM;
    }

    /**
     * 类似于SQL Server系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikeSqlServer() {
        return this == SQLSERVER || this == SYBASE;
    }

    /**
     * 类似于Hive系列的数据库类型
     *
     * @return boolean
     */
    public boolean isLikeHive() {
        return this == HIVE;
    }

    /**
     * 返回引用。
     */
    public String getQuote() {
        return quote;
    }

    /**
     * 是否为MongoDB数据库类型
     *
     * @return boolean
     */
    public boolean isMongodb() {
        return this == MONGODB;
    }

    /**
     * 是否为ElasticSearch数据库类型
     *
     * @return boolean
     */
    public boolean isElasticSearch() {
        return this == ELASTICSEARCH;
    }

    /**
     * 是否为支持使用SQL的数据库类型
     *
     * @return boolean
     */
    public boolean isUseSql() {
        return this != MONGODB && this != ELASTICSEARCH;
    }

    /**
     * 是否为ClickHouse数据库类型
     *
     * @return boolean
     */
    public boolean isClickHouse() {
        return this == CLICKHOUSE;
    }

    /**
     * 判断该 URL 是否为"不支持作为迁移目标端的 SQLite 连接"（移植自 dbswitch 2.0.2）：
     * jdbc:sqlite::resource: 为 classpath 只读资源模式，写入必然失败，应在建池前拦截
     *
     * @param url JDBC 连接串
     * @return true 表示不能作为目标端
     */
    public boolean isUnsupportedTargetSqlite(String url) {
        return this == SQLITE3 && url != null && url.toLowerCase().contains(":resource:");
    }

    /**
     * 返回当前产品不应添加注释的语句或关键字清单。
     */
    public boolean noCommentStatement() {
        return Arrays.asList(
                ProductTypeEnum.MYSQL,
                ProductTypeEnum.TIDB,
                ProductTypeEnum.CLICKHOUSE,
                ProductTypeEnum.MARIADB,
                ProductTypeEnum.GBASE8A,
                ProductTypeEnum.HIVE,
                ProductTypeEnum.SQLITE3,
                ProductTypeEnum.SYBASE,
                ProductTypeEnum.DORIS,
                ProductTypeEnum.STARROCKS,
                ProductTypeEnum.TDENGINE,
                ProductTypeEnum.INFLUXDB3
        ).contains(this);
    }


}
