# 平台集成接口到真实引擎的验收

本验收从 DataForge 集成 API 创建任务和保存图开始，通过现有 DolphinScheduler starter 发布、上线并运行 DS 原生任务，最后读取独立 PostgreSQL 目标表逐行核对。任务处理进程属于 DS Worker；DataForge 只保存配置、提交命令、对账实例，不启动本地 ETL 进程。

## 独立环境

先按 [README.md](README.md) 准备 DS、三个 CLI 和 JDBC 驱动。另建一次性 PostgreSQL 16 和 Redis 7，使用未被占用的本地端口；不要复用现有业务库，也不要停止已有 IDE 后端。让 PostgreSQL 容器加入 DS Compose 的网络，Worker 使用容器名访问 PostgreSQL，测试后端使用本地映射端口。导入完整 `sql/postgresql/datahub-center.sql`，在平台登记该连接并记录数据库编号。

独立样例使用如下表结构，源数据包含空格、单引号以核对 SQL 转义和实际值：

```sql
CREATE TABLE public.native_source(id bigint PRIMARY KEY, name varchar(100));
INSERT INTO public.native_source VALUES (1, '  alice  '), (2, 'Bob'), (3, 'q''Data');
CREATE TABLE public.native_target_datax(id bigint, name varchar(100), label varchar(100));
CREATE TABLE public.native_target_spark(LIKE public.native_target_datax);
CREATE TABLE public.native_target_flink(LIKE public.native_target_datax);
```

DataX 在 DS 数据源中心登记独立 PostgreSQL 连接，通过 `dataforge.integration.database-bindings[平台数据库编号].ds-datasource-id` 和 `.ds-type=POSTGRESQL` 关联；连接凭据保留在数据源中心。Spark/Flink 使用管理员已建的 JDBC Catalog/外部表，通过 `native-table-bindings` 命名空间和可选 `table-aliases` 关联逻辑表，不把连接密码放进节点 JSON。无凭据的本地 trust 样例可由管理员初始化 SQL 建外部表；Flink JDBC 的 `username`/`password` 要么同时提供，要么都省略，本次省略两者并在独立库授予 Worker 同名 OS 用户 `datahub` 最小权限。此方式仅用于隔离样例。

以 `0600` 的本地 properties 配置一个新的测试实例：

- `server.port=50025`；master/slave 均指向独立库；Redis 指向独立 Redis；保留项目既有登录/RBAC。
- `spring.cloud.nacos.discovery.register-enabled=false`、`spring.cloud.nacos.config.enabled=false`、`xxl.job.enabled=false`，不注册成现有业务实例。
- `spring.cloud.openfeign.client.config.system-server.url` 指向可用的系统服务，供负责人查询和鉴权。
- `dolphinscheduler.server-url`、`dolphinscheduler.token` 来自专用 DS 项目；token 不入库、不提交到 Git。
- `dataforge.integration.ds-project-code`、`.tenant-code=datahub`、`.worker-groups[0]=datahub-integration`。
- `.engine-environment-codes.DATAX/SPARK/FLINK` 分别填准备脚本生成的环境编码。
- `.spark-master=local[1]`、`.flink-deploy-mode=LOCAL`；生产也可使用受管 Spark standalone/Flink remote endpoint，页面不接受任意 CLI 参数。
- `.native-table-bindings[编号].spark-namespace[0]=spark_catalog`、`[1]=default`；Flink 对应 `default_catalog`、`default_database`。

本次测试沿用 `application-local` 已有的 mock 协议；没有修改安全开关。其他环境必须使用具有集成 query/create/update/run 权限的正常用户令牌。

先构建，再将 jar 复制到独立临时路径启动，避免后续 Maven 替换正在运行的 jar：

```sh
mvn -pl yudao-module-dataforge/yudao-module-dataforge-server -am package -DskipTests
cp yudao-module-dataforge/yudao-module-dataforge-server/target/yudao-module-dataforge-server.jar /tmp/dataforge-integration-server.jar
java -Xms256m -Xmx1536m -jar /tmp/dataforge-integration-server.jar --spring.config.additional-location=file:/tmp/dataforge-integration-private.properties
```

## 可复现搬数脚本

新建仅本机可读的 JSON，替换以下占位值：

```json
{
  "apiBase": "http://127.0.0.1:50025/admin-api/dataforge/integration",
  "apiToken": "本地测试用户令牌",
  "databaseId": 990001,
  "workerGroup": "datahub-integration",
  "pgContainer": "独立PostgreSQL容器名",
  "pgDatabase": "独立样例库名",
  "engines": ["DATAX", "SPARK", "FLINK"]
}
```

```sh
chmod 600 /tmp/datahub-platform-private.json
python3 deploy/dolphinscheduler-local/platform_smoke.py --config /tmp/datahub-platform-private.json
```

脚本要求每个目标表为空；已有结果会报错，绝不清空。它使用现有图结构的表输入/表输出；Spark/Flink 增加常量组件，严格核对三行 `q'Data`。DataX 复制两列，目标 label 保持 NULL。报告默认写 `/tmp/datahub-integration-<engine>-evidence.json`（0600）；包含任务、流程和真实 DS 实例编号及目标行，不含令牌。工作流和任务保留供审查，不自动删除。

接口顺序为 `POST /create` → `POST /check` → `PUT /flow` → `PUT /status` → `POST /run` → 轮询 `GET /records`；同时可读 `GET /get` 和 `/record-statistics`。`flowJson` 沿用现有 ETL nodes/edges，节点配置位于 `properties.nodeJson`，可为对象。草稿保存不发布 DS，正式保存必须通过同一编译器检查。

## 调度、实时与状态语义

`PUT /schedule` 真实创建/更新 DS Cron 并上下线；开启需任务已上线。开启期间下线/改图被拒绝。GET 同时核对 DS 工作流发布状态、调度状态和实例；本地请求成功仅表示命令已接受，不能显示任务成功。未知网络提交保留待确认记录，不自动重试造成重复搬数。页外长跑实例按实例编号单独回查。

Flink REALTIME 必须绑定真正的持续 Source，并由管理员配置 `native-table-bindings[编号].flink-continuous-source=true`；同时必须把逻辑源表名加入 `flink-continuous-tables` 精确白名单（默认空）。普通 JDBC 全量表默认 false，检查会拒绝把有界搬数当作持续采集。该标志和白名单不能由节点或浏览器设置，管理员必须确保 Catalog 指向实际 Kafka/CDC 等持续源。例如仅对逻辑表 `realtime_events` 配置 `.flink-continuous-source=true` 和 `.flink-continuous-tables[0]=realtime_events`；同 Catalog 中其他 JDBC 表仍不得作为实时源。本次平台 REALTIME 验收使用管理员将独立逻辑表映射到 datagen 持续源，普通表输入→常量→表输出图在 DS 实例 37 中持续落库，确认至少新增 3 行后通过平台停止，核对进程树退出和 activeRunning 归零。它证明平台持续源控制/执行链路，不能代替实际 Kafka/CDC 连接器验收。结果见 [platform-api.json](verification/platform-api.json)，独立引擎验收见 [VERIFICATION.md](VERIFICATION.md)。不接受 `sql-script` 隐藏节点绕过数据源绑定。

停止先持久保存意图，再向 DS 发送 STOP；持续轮询到真实终态。DS 3.4.2 可能在成功杀死进程树后返回 FAILURE，本平台将“已请求停止 + 实际 FAILURE”映射为空闲/取消，同时消息保留真实远端 FAILURE。失败传播包装器确保 Flink CLI 的 SQL 错误退出非 0，不会仅凭 CLI 的 exit 0 宣称成功。

支持范围以 [CONNECTOR_BINDINGS.md](CONNECTOR_BINDINGS.md) 和前端能力矩阵为准。增量游标、未绑定的安全凭据连接器、未实现的转换语义或独立节点失败/超时策略会明确拒绝；可以保存草稿，不能发布成假可执行任务。

## SQL 执行顺序

1. 新库：执行 `sql/postgresql/datahub-center.sql`，基线已包含 0252，无需再补一次迁移。
2. 已有库：先确认已应用至 `sql/postgresql/migrations/0250_dataforge_remove-access-overview-menu.sql`，且存在集成任务/记录表，再执行 `sql/postgresql/migrations/0252_dataforge_integration-ds-runtime.sql`。其他业务的 0251 文件按其独立发布计划处理，不改写也不跳过其所属业务要求。

0252 已在独立 PostgreSQL 实测全新基线、旧基线升级、迁移重复执行、最终 schema 等价和真实 DS 绑定唯一性/调度约束；没有应用到现有业务库。迁移不会自动启动 DS，也不会把迁入历史标为当前运行实例。
