# DB Switch 数据源命名空间

本文仅说明数据源配置如何选择表级元数据与迁移 API 的 `schemaName` 参数，不代表数据库产品的 metadata、DDL、read、write、sync 全部操作均受支持。

公共入口为 `core.service.DatabaseNamespaceResolver.resolve(product, databaseName, schemaName)`。它无状态、无连接、无新依赖；不解析 JDBC URL，不读取租户或授权上下文，也不接管连接/事务。返回值只是配置名称，不是目录对象存在性或访问权限的证明。调用方继续通过既有元数据接口和授权流程验证对象。

所有产品的非空白显式 `schemaName` 优先，保留原始大小写与有意义的前后空格，不 `trim`、不按点拆分；选中的名称含 NUL 时拒绝。没有可靠配置时返回 `Optional.empty()`，表操作由业务报告缺失配置；连接探测无需伪造 schema。`UNKNOWN`/空产品不推断缺省值。

## 27 个产品的配置规则

“库名回退”表示显式 schema 缺失时使用 `databaseName`，不是修改 JDBC 连接目录。“显式 schema”表示缺失时不可把连接数据库或登录用户名当作 schema。OceanBase 尤其不可在未观测兼容模式时按 MySQL 推断。下表中的实现均位于当前 `yudao-dbswitch-product-*` 模块；共享 JDBC 行为位于 core `AbstractDatabase`。

| 产品枚举 | 缺少显式 schema 时 | 当前实现依据 | 本次验证层次 |
| --- | --- | --- | --- |
| MYSQL | 库名回退 | `DatabaseMysqlImpl.querySchemaList/queryTableList`，catalog / `TABLE_SCHEMA` | 契约单测 + MySQL 8.4.8 实库 |
| ORACLE | 要求显式 owner/schema | `DatabaseOracleImpl.queryTableList` 按 `all_tab_comments.owner` 查询 | 契约单测、源码核对 |
| SQLSERVER2000 | 要求显式 owner/schema | `DatabaseSqlserver2000Impl.queryTableList`；继承 SQL Server schema 语义 | 契约单测、源码核对 |
| SQLSERVER | 要求显式 schema | `DatabaseSqlserverImpl.querySchemaList/queryTableList` 的 `TABLE_SCHEMA` | 契约单测、源码核对 |
| POSTGRESQL | 要求显式 schema | `DatabasePostgresImpl.queryTableList` 的 namespace；catalog 与 schema 独立 | 契约单测 + PostgreSQL 16 实库 |
| GREENPLUM | 要求显式 schema | `DatabaseGreenplumImpl.querySchemaList` 与 PostgreSQL namespace 目录 | 契约单测、源码核对 |
| MARIADB | 库名回退 | `DatabaseMariaDBImpl` 继承 MySQL metadata | 契约单测、源码核对 |
| DB2 | 要求显式 schema | `DatabaseDB2Impl` 继承 `AbstractDatabase` JDBC `getSchemas/getTables` | 契约单测、源码核对 |
| DM | 要求显式 owner/schema | `DatabaseDmImpl.queryTableList` owner 参数 | 契约单测、源码核对 |
| KINGBASE | 要求显式 schema | `DatabaseKingbaseImpl` 的 namespace 目录及 JDBC schema 参数 | 契约单测、源码核对 |
| OSCAR | 要求显式 schema | `DatabaseOscarImpl` 与 `AbstractDatabase` JDBC schema 参数 | 契约单测、源码核对 |
| GBASE8A | 库名回退 | `DatabaseGbase8aImpl` 继承 MySQL metadata | 契约单测、源码核对 |
| HIVE | 库名回退 | `DatabaseHiveImpl` 的 database.table 引用及 Hive JDBC schema 参数 | 契约单测、源码核对 |
| SQLITE3 | `main` | `DatabaseSqliteImpl.schema` 已有空值到 main 规则；显式 attachment 不改写 | 契约单测 + SQLite 内存实库 |
| SYBASE | 要求显式 owner/schema | `DatabaseSybaseImpl` 单独保存连接 catalog，schema 由 JDBC 元数据使用 | 契约单测、源码核对 |
| DORIS | 库名回退 | `DatabaseMysqlCompatibleOlapImpl` 继承 MySQL；Doris 引用 database.table | 契约单测、源码核对 |
| CLICKHOUSE | 库名回退 | `DatabaseClickhouseImpl` 查询数据库列表、按 database 读取表 | 契约单测、源码核对 |
| MONGODB | 库名回退 | `DatabaseMongoDBImpl.querySchemaList` 用 `getCatalogs`，`getTables(schemaName, null, ...)` | 契约单测、源码核对 |
| TIDB | 库名回退 | `DatabaseTidbImpl` 继承 MySQL metadata | 契约单测、源码核对 |
| TDENGINE | 库名回退 | `DatabaseTdengineImpl` 的 `SHOW DATABASES` 与指定数据库的 TABLES/STABLES | 契约单测、源码核对 |
| INFLUXDB3 | `iox` | `InfluxNativeCatalog.schema` 仅允许物理 `iox`；连接 database 是独立配置 | 契约单测、源码核对 |
| STARROCKS | 库名回退 | `DatabaseMysqlCompatibleOlapImpl` 继承 MySQL；StarRocks 引用 database.table | 契约单测、源码核对 |
| HIGHGO | 要求显式 schema | `DatabaseHighgoImpl` 继承 PostgreSQL，具有独立 namespace 目录 | 契约单测、源码核对 |
| OPENGAUSS | 要求显式 schema | `DatabaseOpengaussImpl` 继承 PostgreSQL，具有独立 namespace 目录 | 契约单测、源码核对 |
| ELASTICSEARCH | `es` | `DatabaseElasticsearchImpl.querySchemaList` 固定返回 es，索引表描述同样使用 es | 契约单测、源码核对 |
| OCEANBASE | 要求显式 schema | `DatabaseOceanbaseImpl` 在连接上观测模式后委托 MySQL/Oracle；纯配置解析不猜模式 | 契约单测、源码核对 |
| UXDB | 要求显式 schema | `DatabaseUxdbImpl` 继承 PostgreSQL，使用独立 `ux_namespace` | 契约单测、源码核对 |

共 10 个库名回退、14 个显式 schema、3 个已有固定/默认命名空间。Oracle/DM 的登录用户名不是 owner 的可靠替代：名称大小写、当前 schema、跨 owner 访问可能不同。PostgreSQL/SQL Server 的 `public`/`dbo` 也不是可靠的全局默认，用户必须选择实际 schema。InfluxDB 当前适配器的物理 schema 为 `iox`，不是 `public`。显式名称即使不符合特定产品的物理限制，仍交由已有产品 API 验证，不静默换成另一个命名空间。

## 验证与边界

- core `DatabaseNamespaceResolverTest` 对全部 27 个产品及 UNKNOWN 验证显式名称、缺失 schema 分类，另验证默认值、空白、NUL 与精确名称保留。
- starter `DatabaseNamespaceJdbcTest` 通过独立 MySQL/PostgreSQL 临时实例，验证解析结果用于真实的表列表、字段和主键元数据；SQLite 内存实例验证 main 与显式 attached 数据库不会混淆。
- starter `RelationIdentityAutoConfigurationTest` 验证现有最小上下文。
- 其余产品本次只做配置契约测试和现有实现核对，未启动对应真实数据库。不能将 27 个产品的参数化测试表述为 27 种数据库实库验证。

实库用例只创建随机测试 database/schema，并在 finally 中清理。MySQL/PostgreSQL 用例须分别提供 `datahub.test.namespace.mysql.url=jdbc:mysql://127.0.0.1:<port>/` 和 `datahub.test.namespace.pg.url=jdbc:postgresql://127.0.0.1:<port>/postgres`；缺少对应参数会明确跳过，交付时必须检查执行/跳过数。
