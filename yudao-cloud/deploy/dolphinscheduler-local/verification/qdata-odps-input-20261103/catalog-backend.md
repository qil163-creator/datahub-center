# ODPS 只读目录后端验证

验证日期：2026-11-03。该记录仅覆盖本次目录源码与模拟传输验证，不代表云端凭据或正在运行的服务已验收。

## 实现范围

- DB Switch 新增可选 `CatalogMetadata` SPI，通过 `DbSwitchRuntime.catalogMetadata` 获取；保留既有描述器构造调用和类型映射复制行为。
- ODPS 组件使用当前依赖 `odps-sdk-core:0.52.3-public` 的 `ResourceBuilder`、`RestClient` 签名和类型解析读取项目的表列表及指定表结构。响应字段与 SDK 的 `Tables.ListTablesResponse`、`Table.TableModel`、`ColumnUtils.fromJson` 契约对照。
- `DatabaseServiceImpl.getTableList/getTableMeta` 通过已存、已授权的平台连接调用目录 SPI；凭据只传入组件，响应保留原生字段类型、顺序、明确的可空性与分区标记，未知主键、长度、精度等保持 `null`。
- `DatabaseTableColumnRespVO.partitionKey` 为新增可空字段；原 JDBC 分支和原有字段不变。

没有更改 ODPS 既有 `METADATA`、JDBC、`READ` 能力声明，没有启用 SQLTask、Tunnel SQL、任意 SQL、增量读取或云端作业。普通项目和表标识符之外的三级命名等范围不在本次实现内。

## 传输边界

项目探测与目录共用受限 Transport。目录要求 HTTPS、相同目标与固定资源路径、只读 GET、允许的查询参数、SDK `curr_project` 与项目一致，不跟随重定向，不开放流式连接或代理覆盖。共享截止时间限制整个调用；每次响应最多 64 KiB、每次目录调用最多 100 个请求、最多 5000 个表或列；异常包含安全的固定提示，不透出远端正文和凭据。XML 禁止 DTD 与外部实体，连接和输入流均释放。

SDK 的 `Table` 类在当前排除 Arrow/Netty 的依赖范围会触发类验证依赖，因此本次直接使用 SDK 的 REST 资源和签名，避免加载 `Table`、引入 Arrow 或触发 SDK 对特殊表的 SQL 回退。

## 已执行验证

```sh
mvn -pl :yudao-dbswitch-product-odps -am -DskipTests compile
mvn -pl :yudao-dbswitch-product-odps -am -Dtest=CatalogMetadataContractTest,OdpsCatalogMetadataTest,OdpsDiagnosticTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl :yudao-module-dataforge-server -am -Dtest=DatabaseOdpsCatalogTest,DatabaseServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test
python3 tools/quality/run_all.py
git diff --check
```

- 编译通过。
- 组件与 SPI：14 个测试通过，0 失败、0 错误、0 跳过；其中 SPI 3 个、目录 10 个、既有项目探测 1 个。
- 消费端：21 个测试通过，0 失败、0 错误、0 跳过；其中目录消费 4 个、既有数据源服务 17 个。
- 质量门禁：DB Switch 0 违规；130 个迁移文件有效；错误码及 182 个 POM、6316 个 Java 文件的模块边界检查通过，历史债务按现有基线处理。
- 差异空白检查通过。

目录测试使用注入的模拟 `HttpURLConnection`，验证 SDK 实际请求签名路径、分页、类型与零小数位、未知元数据、非法请求、权限失败、重定向、超限、实体、取消和资源释放；没有访问云服务。既有项目探测测试只使用本地 HTTP fixture。消费端测试验证存储连接解密边界、权限短路、无 JDBC 调用及错误脱敏。

本次未启动、停止或重启任何前后端服务。没有 SQL 迁移或数据库写操作。运行中的后端需由既有开发流程载入新源码；真实云目录联调仍需具有对应目录权限的平台 ODPS 连接。
