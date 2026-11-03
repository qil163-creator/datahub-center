# 多协议连接器接入与验证

本阶段复用现有数据连接和连接器管理入口，只补充创建、编辑及测试连接。新增类型仅声明 `CONNECT`，不开放元数据、数据读写、DDL 或同步；现有类型继续使用原能力矩阵。

## 组件归属与复用

| 范围 | 公开技术入口 | 领域接入 |
| --- | --- | --- |
| 数据库及原生数据服务 | DB Switch `ConnectionProbe`、`DbSwitchRuntime.probeConnection` | DataForge `ConnectionOnlyDiagnosis` |
| OSS / MinIO | 既有 `S3FileClient.testConnection`，复用签名、配置与关闭 | 映射 Endpoint、Bucket、Region 和加密凭据 |
| SFTP | 既有 `SftpFileClient.testConnection`，复用 SSH 会话和严格主机公钥校验 | 映射根目录及主机指纹；本阶段不加入文件管理读写入口 |
| Kafka / RabbitMQ / RocketMQ | MQ starter `ExternalMqConnectionProbe` | 独立外部目标，不修改平台消息总线实例 |
| Redis | Redis starter `ExternalRedisConnectionProbe` | 独立目标，认证、选择 DB、PING；不读取业务键 |

已检索 DB Switch 的产品注册、驱动包与原生探测，以及 biz-file 的 S3/FTP/SFTP、MQ/Redis starter 和 DataForge 外部数据源连接测试、数据推送调用方。文件及消息协议继续由职责对应的既有组件承载，没有新建通用“非数据库”模块，也没有在 DataForge 复制 SDK 协议实现。

qData 本地材料用于核对协议和依赖：`安装包/docker/neo4j` 为部署配置及数据目录，不能作为 JDBC 驱动使用；DataX 插件内已有的 Neo4j、ODPS、Phoenix JAR 版本较旧。实现通过 Maven 声明对应依赖，没有复制部署数据、旧日志框架或真实凭据进入仓库。

Neo4j 独立服务部署样例和填写说明见 [Neo4j 部署与连接](../yudao-framework/yudao-dbswitch/yudao-dbswitch-product/yudao-dbswitch-product-neo4j/deploy/README.md)。已有服务可直接填写地址；需要本机开发实例时使用样例创建独立数据卷。qData 原目录中的认证状态、数据库、事务日志、锁和历史日志不属于项目源代码，不随驱动接入复制。

## 新增目录

连接器目录由迁移 `0197_dataforge_connect-only-connectors.sql` 补齐 16 项：

- JDBC：`GBASE8C`、`GAUSSDB`（DWS）、`PHOENIX`（Query Server thin）、`SAPHANA`、`PRESTO`。
- 原生数据服务：`ODPS`、`NEO4J`、`MILVUS`、`INFLUXDB2`。
- 文件、缓存及消息：`OSS`、`MINIO`、`REDIS`、`KAFKA`、`RABBITMQ`、`ROCKETMQ`、`SFTP`。

标准新库目录由原有 30 项补充到 46 项；已有库保留自定义项和原编号，实际总数可能不同。

保留现有 InfluxDB 3 / Flight SQL；InfluxDB 2.x 使用独立 Org、Token、Bucket 模型。GBase 8c 不等于 8a/8s，GaussDB DWS 不等于 GaussDB OLTP，PrestoDB 不等于 Trino。Phoenix 不接受 ZooKeeper/HBase thick 配置冒充 thin 模式。

## 配置与凭据契约

保留 `/dataforge/database/**`、既有连接表和类型表。新类型的用途固定为业务库，不能用仅测连类型充当中台写入库。

非 JDBC 扩展参数是 `schemaVersion: 1` 的 JSON，白名单键包括 `endpoint`、`servers`、`basePath`、`hostKeyFingerprint`、`region`、`pathStyle`、`securityProtocol`、`saslMechanism`、`virtualHost`、`topic`、`tls`、`authenticationMode`、`timeoutSeconds`。`servers` 可为主机端口字符串数组；开关必须是 JSON boolean。`timeoutSeconds` 为可选的 1～30 整数，默认 10，传入所选协议组件；旧记录无需补写即可使用。新增 JDBC 仍沿用原 URL 参数契约，不把原生 JSON 拼进 JDBC URL；组件负责不同产品的分隔符和编码，并拒绝扩展参数中的凭据。

Phoenix Query Server 的认证属性可在普通连接参数中显式填写 `authentication=BASIC` 或 `authentication=NONE`；用户名和密码通过专门字段交给 Avatica，不能放在 URL 参数里。本阶段不承诺 Kerberos 配置。Neo4j、Milvus 的数据库名称必填，向导默认分别为 `neo4j`、`default`。

账号及 AccessKey 标识映射 `userName`；密码、Secret、Token 进入既有加密 `password` 列。InfluxDB 2.x 的 `userName` 表示 Org，`databaseName` 表示 Bucket；ODPS 的 `databaseName` 表示 Project。禁止在 JSON、Endpoint 或日志中携带秘密。

详情仅返回 `hasPassword`，不返回密码或记录密钥。编辑同一目标时空密码保留原秘密；只修改超时、JSON 键顺序或补写等价默认值也可保留。目标、用户或认证参数改变后，保存与测试均要求重新输入已有秘密。显式切换 Milvus / RocketMQ 到 `NONE` 则清空旧秘密，不继续发送旧凭据。客户端不能指定记录加密密钥。

创建和编辑不发起外部 I/O；测试通过统一探测路径执行。已保存测试按配置快照和数据库时钟更新状态，旧配置或旧请求不能覆盖新状态。缺少驱动、组件繁忙或中断应显示未验证，不能冒充已连接。

## 运行时条件

- SFTP 当前为密码认证，必须配置可信的 OpenSSH SHA-256 主机公钥指纹。仅证明连接及根目录访问，不能推断上传/删除权限。
- S3 使用指定 Bucket 的只读 HEAD；缺桶权限、错误签名及不存在桶不会判为成功。不通过临时写文件验证连接。
- Redis 首阶段面向独立单机地址。Kafka 支持 PLAIN/SCRAM 及相应 TLS/SASL 协议。RabbitMQ 验证指定 vhost。
- RocketMQ 首阶段为 Remoting 模式，需要读取 Broker 运行信息的权限。只连通 NameServer 不算成功；不支持 Proxy、ACL 2.0 或 TLS 模式的推断兼容。
- Neo4j 使用原生驱动验证并对目标数据库执行固定只读语句。Milvus 使用 REST v2 的目标数据库请求并检查业务返回码。InfluxDB 验证所填 Org/Bucket 及 Token，不能只看公开 health。
- ODPS 使用 SDK 进行真实项目请求，不提交计算作业；传输有超时、响应大小上限且不跟随跨目标重定向。
- GBase 8c 等未内置厂商驱动的路径，必须由运维配置当前 DB Switch 可信 driver profile/bundle；缺依赖诊断会明确提示。目录存在不代表已安装厂商驱动，更不代表实机验证通过。

当前数据连接入口使用产品默认的服务端 `drivers` 配置；组件的命名 `profileId` 扩展点尚未暴露为表单选项。不能仅登记一个命名 profile 就假设页面会自动选中它，需为对应产品登记默认的可信驱动包。

HTTPS/TLS 保持系统信任链及主机名校验。SFTP 保持严格主机公钥校验。自定义客户端证书、STS、Kerberos、SFTP 私钥等额外认证模式没有在本阶段隐式开放。

## 协议填写与本轮补全

| 类型 | 配置与实际连接路径 |
| --- | --- |
| Neo4j | Bolt 地址、用户名、密码、目标库；默认 `neo4j`。7474 是 HTTP 管理端口，Bolt 默认 7687。支持 `bolt`/`neo4j` 及 `+s`，目标库单独填写；路由地址须从后端可达。 |
| InfluxDB 2 | HTTP(S) Endpoint、Org 名称或 16 位 Org ID、Bucket、Token。限权 Token 建议填写 Org ID，避免依赖组织目录读取权限。 |
| Milvus | REST v2 Endpoint、目标库、NONE/PASSWORD/TOKEN；严格验证 HTTP 和业务成功码，整数溢出或缺少 code 不算成功。 |
| ODPS | 地域对应 Endpoint（通常含 `/api`）、Project、AccessKey/Secret；通过 SDK 签名请求项目，不提交作业。 |
| OSS | S3 兼容 Endpoint、Bucket、Region、AccessKey/Secret；默认虚拟主机寻址，验证指定桶访问。 |
| MinIO | S3 API Endpoint、Bucket、Region、AccessKey/Secret；默认路径寻址，不能填 Console 管理端口。 |
| SFTP | SSH 主机/端口、用户名/密码、绝对根目录、可信 SHA-256 主机指纹；握手和目录检查按测连预算执行。 |
| Redis | 单节点主机/端口、DB 编号、可选密码/ACL 用户、TLS；认证后 SELECT/PING。 |
| Kafka | Bootstrap 地址列表（最多 32）、安全协议、PLAIN/SCRAM 凭据、可选 Topic；不生产或消费消息。 |
| RabbitMQ | AMQP 主机/端口、用户名/密码、Virtual Host、TLS；不使用 HTTP 管理端口。 |
| RocketMQ | Remoting NameServer 列表（最多 8）、NONE/ACL、可选 Topic；验证 Broker 信息，不能使用 Proxy 地址代替。 |
| GBase 8c | 专用 `jdbc:gbase8c:` 驱动配置及目标库；服务端须安装可信厂商驱动，不能借用 GBase 8a/8s。 |
| GaussDB DWS | PostgreSQL 协议地址、库、schema、凭据；仅针对 DWS 兼容路径。 |
| Phoenix | Query Server thin URL；可选 `authentication=BASIC/NONE`，账号密码通过专门字段传递。 |
| SAP HANA | HANA JDBC 地址、端口、库/schema 与凭据；端口按目标实例实际配置填写。 |
| PrestoDB | Presto JDBC 地址、catalog/schema 与用户；不把 Trino 作为同一产品处理。 |

DB Switch 测连诊断采用固定类别区分认证失败、无权访问、目标不存在、TLS 验证失败、DNS、端口、超时和缺少依赖；不回显厂商异常、账号或地址。Neo4j 关闭 SDK 的默认地址日志，独占单连接和单事件线程，关闭重试及遥测，结束后释放驱动和会话。其他组件仍按各自的稳定异常契约转换，不能把未知错误伪装成具体认证失败。

2026-10-19 使用 REA 重新读取演示站新建连接表单。观察到 Neo4j 配置为 IP、端口、账号、密码、可空数据库（默认 `neo4j`），页面没有 TLS 开关；39 种类型中没有独立 SFTP。因此本项目的明确 Bolt URL、TLS、严格主机公钥、可配置超时和失败分类是本项目的实现设计，并非声称演示站已有这些能力。REA 表单证据：`ev_61d04c2eba97b71dfa4be947a72b661c17615227b6409195a511ec1eae63539c`；源捕获证据：`ev_be5cba711ad666d9395f8352792c06f2cb95dd2056f8742f6aea18b9a779337a`。演示站只进行查看，没有保存连接或发起对外测连。

## 数据库升级

已有库在已完成 `0196_asset_remove-unused-persistence.sql` 等前置迁移后执行：

`sql/postgresql/migrations/0197_dataforge_connect-only-connectors.sql`

迁移保留现有编号、引用、非空描述、模板和停用状态，补齐协议字典并扩容主机、账号字段；声明可重放。新库直接使用包含本迁移的 `sql/postgresql/datahub-center.sql`，已有库不能重新导入全量基线。

## 验证方式

后端使用项目要求的 JDK 25。指定测试跨 reactor 运行须加 `-Dsurefire.failIfNoSpecifiedTests=false`；Windows PowerShell 中把完整 `-D...` 参数加引号。

- DataForge：`ConnectionSettingsTest`、`ConnectionOnlyDiagnosisConverterTest`、`ConnectionOnlyDatabaseServiceTest`、`ConnectionOnlyDiagnosisMappingTest`；后者检查超时、目标和凭据确实传到组件公开入口。
- PostgreSQL：`ConnectionOnlyCatalogPostgreSqlTest` 验证新库、已有目录升级、重复执行和引用保护；仅接受 `DATABASE_TYPE_CATALOG_TEST_JDBC_URL` 指向本机隔离控制库，由用例创建、删除随机任务库。
- 文件、MQ、Redis：组件聚焦测试验证签名、认证、无写入副作用、错误分支和清理。`ExternalS3NativeTest`、`ExternalMqNativeTest`、`ExternalRedisNativeTest` 在显式提供任务环境变量时验证真实服务；未提供目标而跳过的项目不算通过。
- DB Switch：CONNECT SPI、能力隔离、驱动缺失、受限 HTTP 响应、原生请求和资源预算测试。
- 安全诊断：`ConnectionProbeDiagnosticsTest`、`HttpConnectionProbeSupportTest`、`NativeProbeResponseTest` 验证固定错误类别、HTTP 状态、组织解析和 Milvus 业务返回码边界。
- 真实 Neo4j / InfluxDB 2.x：`NewConnectionNativeTest` 仅接受显式指定的本机测试服务；覆盖正确与错误凭据、缺失数据库、Org 和 Bucket，服务未提供时跳过，不能算作实机通过。
- 前端：协议编解码、秘密剥离、认证模式切换、CONNECT 门禁、异步状态及创建/编辑流程的 Vitest、类型检查和 lint。
- 交付门禁：`python tools/quality/run_all.py`，以及前端活动应用质量门禁。

厂商数据库、云端项目和服务版本的实际兼容性需要对应真实目标验证；协议夹具、编译成功和枚举声明不能替代目标验收。

2026-10-19 本轮验证结果：

- 组件聚焦回归 51 项、DataForge 回归 45 项全部通过，0 跳过；包含最小 Spring 上下文和 JSON 脱敏契约。超时与版本整数边界测试另行复核通过。
- Neo4j 4.4.40、5.26.32 各执行 5 项真实网络测试，另执行 3 项 4.4.40 的 DB Switch 运行时测试，均通过且无跳过；新 Compose 实际启动并达到健康状态。有效 CA 证书的 TLS 正向路径和集群路由尚未实测。
- InfluxDB 2.7.12 执行 4 项实机测试，无跳过；SFTP 测试包含真实 SSH/SFTP 会话及无响应对端。测试用容器、卷、网络和临时凭据已清理，保留已拉取的官方镜像便于复用。
- `python tools/quality/run_all.py` 和 `git diff --check` 通过。当前本轮没有新增平台结构或目录数据变更，无新增 SQL 迁移；首次启用这 16 项仍须完成上文的 `0197`。
- 前端完整 `quality:antdv-next`：294 个测试文件、2810 项测试通过，0 跳过，边界检查、lint、类型检查、生产构建通过。随后补充 Neo4j 地址边界检查，34 项定向复核、修改文件 lint 和再次完整类型检查通过；此增量没有再次重复全量构建。未进行本项目全栈浏览器端到端验收。
- 本轮未重跑未修改的 S3、Kafka、RabbitMQ、Redis 实机用例；RocketMQ、Milvus、ODPS 和厂商数据库仍缺少相应真实目标的本轮验收记录。没有用 qData 演示站代替本项目测连验收。
