# DolphinScheduler 本地验收环境

基于官方 `apache/dolphinscheduler-standalone-server:3.4.2` 构建本地镜像，补充 `psmisc` 中的 `pstree`，并安装真实的 DataX、Spark、Flink CLI。API/UI 仅绑定 `127.0.0.1:12345`，与现有 Doris、XXL-Job 容器分开管理。DS 服务使用镜像自带 Java 8；Spark/Flink 任务使用独立的 `/opt/java17`。部署与执行引擎的区别见 [官方 Docker 指南](https://github.com/apache/dolphinscheduler/blob/3.4.2/docs/docs/en/guide/start/docker.md)。

| 引擎 | 官方来源 | Worker 目录 | 本地部署模式 |
| --- | --- | --- | --- |
| DataX | [Alibaba 202308 发行包](https://github.com/alibaba/DataX/blob/master/README.md) | `/opt/datax` | DS 原生 DATAX 插件 |
| Spark 3.5.6 | `apache/spark:3.5.6-java17-scala` | `/opt/spark` | `local[1]`，DS 原生 SPARK 插件 |
| Flink 1.20.3 | `flink:1.20.3-scala_2.12-java17` | `/opt/flink` | `LOCAL`，DS 原生 FLINK 插件 |

三个引擎的可执行程序同时安装在受管 Worker 中；本地模式按任务启动独立引擎进程，避免常驻集群耗尽共享 Docker VM 内存。引擎验收按顺序执行。

## qData 集成页面与执行覆盖

项目保留现有导航，复刻离线/实时任务列表、新增/任务配置、流程设计器、组件配置、详情、调度与实例入口。组件面板的 49 项均有配置入口，并复用现有 ETL 编辑器契约；配置可保存并不代表 49 项均能提交原生引擎执行。

| 配置分组 | 数量 | 当前执行边界 |
| --- | --- | --- |
| 输入 | 16 | 表输入通过平台连接和管理员原生 Catalog/DS datasource 绑定执行。DataX CSV、HDFS、MongoDB 有受管无凭据编译；其他输入保留配置，缺少安全适配/插件时检查明确拒绝 |
| 脚本执行 | 2 | Java 脚本与自定义 token 配置有编辑入口；当前集成 DS 编译不执行它们。现有 ETL 隔离 Java worker 不等于已实现 DS 任务适配；token 还需要受管认证连接、运行期秘密变量和依赖边界 |
| 转换 | 19 | 按实际编译器开放子集：常量、字段规则、有限派生/拆分/映射、整行批去重等。序列 ID、字符串填充、Java 正则替换、字段加解密、计算器等尚未适配的配置会被拒绝；行转列与实时全局排序/去重/Join 需要确定的排序/窗口/状态边界 |
| 流程控制 | 2 | 条件分发保存具名执行端口，按首个命中分支路由；缺失绑定、未连接分支及未适配策略会被拒绝。子流程可配置具名输入/输出，但当前 DS 集成编译尚不展开子流程执行 |
| 输出 | 10 | 表输出仅向已有目标插入，不允许隐式清空、建表或更新。DataX HDFS/MongoDB 提供上述受管路径；其他输出依插件和安全凭据绑定逐项开放 |

Spark/Flink 的表节点使用管理员配置的原生 Catalog，平台每次先核对数据库访问与真实表字段，运行时由 DS 原生插件调用引擎。转换的具体可用性取决于引擎、批/流模式、字段类型及选项，检查会拒绝未实现语义，不能从工具箱图标推断支持。表单中的分隔符、字段类型、增量模式、写入模式、重试和调度等参数都以最终服务端校验为准。

实时 Flink 表输入还要求管理员对该数据连接明确配置 `native-table-bindings.<databaseId>.flink-continuous-source=true`，以证明原生表是持续 CDC/流来源；普通 JDBC 有界扫描不能仅因任务类型是“实时”就冒充持续监听。UUID 生成仅允许紧接唯一表输出，防止在多输出 SQL 中重复计算而使分支路由失真。数值型值映射的 `KEEP`、未验证的混合类型提升等无法无损表达的配置也会明确拒绝。

六种 DataX 无凭据编译及绑定要求见 [CONNECTOR_BINDINGS.md](CONNECTOR_BINDINGS.md)。本次已真实验收 DataX CSV 读写（中文、逗号引号、表头），重复写入触发 `nonConflict` 且原文件校验值不变；HDFS/MongoDB 仅完成编译/校验测试，尚未连真实外部服务。FTP/ODPS 当前因官方插件需要内联秘密而明确阻止执行；本地发行包没有 Redis/JSON/消息队列/WebService 插件。

复现 CSV 的实际编译产物：

```sh
mvn -q -pl :yudao-module-dataforge-server -am -Dtest=IntegrationDataxFixtureTest -Dsurefire.failIfNoSpecifiedTests=false test
```

产物位于 `yudao-module-dataforge/yudao-module-dataforge-server/target/datax-connector-csv-native.json`，通过受管绑定解析、ETL 连接器编译与现有 DS 参数构造器共同生成，不含凭据。真实引擎验收结果和限制见 [VERIFICATION.md](VERIFICATION.md)。

## 启动和准备

```sh
python3 deploy/dolphinscheduler-local/prepare_runtime.py
docker compose -f deploy/dolphinscheduler-local/compose.yaml up -d --build
docker compose -f deploy/dolphinscheduler-local/compose.yaml logs --tail=60 standalone
python3 deploy/dolphinscheduler-local/smoke.py --prepare-only --output /tmp/datahub-ds-connection.json
```

等待 [本地 DS](http://127.0.0.1:12345/dolphinscheduler/ui/) 可用后执行准备命令；脚本通过隐藏输入读取本地 DS 密码，也支持由调用环境注入 `DS_USERNAME`、`DS_PASSWORD`，不会把凭据写入报告。准备过程创建或复用 `datahub-integration-local` 项目、`datahub` 执行租户和 `datahub-integration` Worker 组；执行租户必须对应容器里的同名系统用户。

`--prepare-only` 输出的 `projectCode` 用于配置 `dataforge.integration.ds-project-code`。同时配置 `dataforge.integration.tenant-code=datahub`、`dataforge.integration.worker-groups[0]=datahub-integration`，任务选择同名 Worker 组。DS 客户端使用 `dolphinscheduler.server-url=http://127.0.0.1:12345/dolphinscheduler`，凭据仅在本地环境或安全配置中心设置。DataX 发行包约 1.65GB，下载脚本会固定校验 SHA-256；`runtime/` 是被 Git 忽略的本地构建输入。

Compose 用 `JAVA_OPTS` 将本地验收 JVM 堆限制在 768MB。镜像创建非 root 的 `datahub` 系统用户，启动时只为日志卷设置归属，随后以 `datahub` 运行 DS 服务和任务；sudo 仅允许切换到自身用户，不能切换 root。这样 Spark 的 Derby metastore、Flink 和文件输出可以写入当前任务目录。镜像启动脚本默认传入 `-Xms4g -Xmx4g`，只设置 `JAVA_TOOL_OPTIONS` 无法覆盖后面的显式 JVM 参数；这里缩小本次验收进程的堆，不修改负载保护阈值。

## 真实执行验收

```sh
python3 deploy/dolphinscheduler-local/smoke.py --output /tmp/datahub-ds-smoke.json
python3 deploy/dolphinscheduler-local/engines_smoke.py --connection /tmp/datahub-ds-connection.json --private-config /tmp/datahub-ds-private.json
python3 deploy/dolphinscheduler-local/engines_smoke.py --connection /tmp/datahub-ds-connection.json --checks compiled --compiled-sql-dir yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-sql --output /tmp/datahub-ds-compiled-smoke.json
python3 deploy/dolphinscheduler-local/engines_smoke.py --connection /tmp/datahub-ds-connection.json --checks stream --output /tmp/datahub-ds-stream-smoke.json
python3 deploy/dolphinscheduler-local/graph_smoke.py --private-config /tmp/datahub-ds-private.json --checks failure --output /tmp/datahub-ds-sql-error-smoke.json
python3 deploy/dolphinscheduler-local/graph_smoke.py --private-config /tmp/datahub-ds-private.json --checks graphs --artifact yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-sql --output /tmp/datahub-ds-graph-smoke.json
python3 deploy/dolphinscheduler-local/graph_smoke.py --private-config /tmp/datahub-ds-private.json --checks mixed --artifact yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-sql --output /tmp/datahub-ds-mixed-smoke.json
python3 deploy/dolphinscheduler-local/graph_smoke.py --private-config /tmp/datahub-ds-private.json --checks budget --artifact yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-sql --output /tmp/datahub-ds-budget-smoke.json
python3 deploy/dolphinscheduler-local/graph_smoke.py --private-config /tmp/datahub-ds-private.json --checks csv --artifact yudao-module-dataforge/yudao-module-dataforge-server/target/datax-connector-csv-native.json --output /tmp/datahub-ds-csv-smoke.json
```

第一个脚本真实验证工作流发布、Shell 执行和日志、Cron 上下线、长任务进程树停止。第二个脚本创建三个受管 DS 环境，分别用原生 `DATAX`、`SPARK`、`FLINK` 任务类型执行数据和 SQL，检查 DS 成功终态及实际输出。它会将环境编码写回 connection JSON，文件不含凭据，可用于项目测试实例的受管配置。报告只包含对象编号、状态和输出标记是否存在。工作流和实例保留在独立测试项目中供核查；脚本不会删除现有对象。这些烟测不能替代项目集成接口到目标数据源的端到端验收。

`--checks compiled` 使用 `EtlNativeSqlCompilerTest` 实际生成的 SQL，核对 Spark 输出 `ALICE\tq'Data`、Flink 输出 `+I[1, ALICE, q'Data]`；需要先运行该聚焦 Maven 测试生成 `target/native-sql/`。`--checks stream` 等待无界 Flink datagen 流实际向 print sink 输出，再停止 DS 实例并核对进程树退出。三引擎默认烟测只验证本地引擎能力，不依赖外部业务库；镜像同时安装 PostgreSQL JDBC 42.7.13 和 Flink JDBC Connector 3.3.0-1.20，可用于独立测试库搬数。

脚本也支持由环境注入 `DS_TOKEN`，不会把 token 写入报告。需要为测试服务生成短期本地 token 时，可给引擎脚本添加 `--private-config /tmp/datahub-ds-private.json`：文件强制权限 0600，token 有效一天，禁止纳入版本控制或粘贴到日志。不要把这个私有文件与无凭据的 connection JSON 混用。

`graph_smoke.py` 对分支两输出、Join、拆分做完整结果行比较；`mixed` 对列转行后的六条完整值（含 NULL 和空格）及每行 UUID 的格式、唯一性做断言。`budget` 先验证正常 Join 的三行结果，再用编译生成的上限 2 配置确认第三行使两个引擎真实失败，不静默截断。CSV模式读取实际 Java compiler/builder 参数，验证中文、带逗号字段与表头，再重复任务确认 `nonConflict` 报 `TxtFileWriter-02` 且输出文件摘要不变。CSV验收要求 `/home/datahub/fixtures/out` 为空，脚本拒绝删除旧输出；再次验收应先将旧结果完整移到另一个目录留存。编译 SQL 按原始 UTF-8 字节读取，避免 Python 换行规范化改写字符串中的控制字符。

镜像内的 `sql-client.sh` 是受管失败传播入口，原始 Flink CLI 保留为 `sql-client-original.sh`。Flink 1.20 可能在 SQL 报错后仍退出 0；入口按二进制行无缓冲透传控制台，只保存固定错误标记，遇到引擎 `[ERROR] Could not execute SQL statement.` 就返回非 0，使 DS 得到 `FAILURE`。`--checks failure` 会故意调用不存在的函数证明该行为；正常 SQL 与实时停止也分别实测，避免将成功状态误当作数据正确。相关结果见 [VERIFICATION.md](VERIFICATION.md)。

DS 3.4.2 的 Shell 停止存在终态差异：实际 `SIGINT` 已终止进程树，但 Worker 可能先上报失败，工作流返回 `FAILURE`。脚本必须同时核对 `process has exited` 和 `Successfully killed process tree`，并明确报告 `PASS_WITH_DS_STOP_STATE_MISMATCH`；不会把停止请求返回成功当作 `STOP`。平台应结合持久停止意图和远端终态进行回查。

停止本次环境使用 `docker compose -f deploy/dolphinscheduler-local/compose.yaml stop`。不要使用 `down -v` 清理其他环境。该 standalone 环境采用镜像默认的内存 H2 元数据，容器进程重启后需重新准备对象、重新填写新项目编码；日志卷不会使 H2 元数据持久化。它用于本地验收，持续运行环境应使用官方分布式部署并配置外部 PostgreSQL/ZooKeeper。

平台 API 到真实 PostgreSQL 目标表的三引擎搬数、Cron 门禁和受管持续源停止验收见 [PLATFORM_API_VALIDATION.md](PLATFORM_API_VALIDATION.md) 与 [无凭据结果](verification/platform-api.json)。

配置页 CSV/Excel/JSONL 文件字段辅助接口、上传者归属校验、只读采样限额、原 5999/50024 的 IDE Rerun 与 mTLS 配置边界见 [FILE_SCHEMA_VALIDATION.md](FILE_SCHEMA_VALIDATION.md)，真实跨服务结果见 [八项断言](verification/file-schema-api.json)。全部 49 组件已补内容、源证据和仍有差异见 [覆盖矩阵](verification/qdata-49-component-audit.md)。


## 数据开发原生任务的本地部署

数据开发的 `LOCAL` 运行档案使用 root 监督器记录真实 CLI、退出码和整个后代树的退出，状态查询使用独立 loopback HTTPS/mTLS。准备脚本无需重启当前 DS；部署、证书路径、只读查询契约及后续可重建镜像见 [NATIVE_RUNTIME.md](NATIVE_RUNTIME.md)。该入口仅允许受管 Spark SQL/Flink SQL，不能据此推断 JAR/Python 本地运行也已支持。

### 普通 DataForge 服务加载本地运行配置

先完成上述原生观察器准备，再从后端仓库根目录执行：

```sh
python3 deploy/dolphinscheduler-local/prepare_dev_native_runtime.py \
  --ds-private /tmp/datahub-ds-private.json \
  --tls-connection /tmp/datahub-native-runtime-connection.json
```

脚本只创建或复用独立 `datahub-development-native-local` 项目和 `datahub-development` Worker 组，核对已有非 root 执行租户、引擎环境编码与观察器 mTLS 连接；不修改环境内容或既有 Worker 关联，不提交或启动工作流，不重启 DS 或 DataForge。DS 私有输入必须属于当前用户且权限为 `0600`。DS 重启导致内存 H2 对象消失时，应先重新准备租户、引擎环境和私有连接，再运行此脚本，不能沿用失效环境编码。

默认输出是被 Git 忽略的 `deploy/dolphinscheduler-local/runtime/dev-native/application-native.yaml`，通过原子替换写成 `0600`。控制台只显示输出路径和无凭据 profile codes：`flink-local-sql`、`spark-local-sql`。文件使用 Spring 支持的 JSON 形式 YAML，包含私有 DS token 与客户端 PEM bundle 路径，禁止复制原文到日志或版本控制。可用 `--output` 指定其他私有路径；仓库内输出必须位于 Git 忽略目录。

两档案分别使用 `LOCAL`、Flink `1.20.3` / Spark `3.5.6`，保留 `local-sql-v1` revision、`default` queue 和 `dev-local-observer` bundle。本地预算由服务端及 Worker 双重校验：并行度、CPU、实例数为 `1`，内存仅允许 `512`、`768`、`1024` MB；Spark 固定 `local[1]`。档案只开放 SQL，不配置 JAR/Python 制品。普通 `dataforge.dev.ds-project-code` 也指向该独立项目；不更改数据集成项目配置。

使用正常 DataForge 服务的本地启动方式，并通过 Spring 的 `additional-location` 追加文件，例如：

```sh
export SPRING_CONFIG_ADDITIONAL_LOCATION="file:${PWD}/deploy/dolphinscheduler-local/runtime/dev-native/application-native.yaml"
java -jar yudao-module-dataforge/yudao-module-dataforge-server/target/yudao-module-dataforge-server.jar \
  --spring.profiles.active=local
```

IDE 的环境变量使用同名 `SPRING_CONFIG_ADDITIONAL_LOCATION`，值填实际绝对 `file:` 路径；也可在程序参数中添加 `--spring.config.additional-location=file:/绝对路径/application-native.yaml`。追加配置会保留应用原有配置搜索位置；不要用 `spring.config.location` 替换整套基础配置，也不要把 token 放进程序参数。加载机制与 PEM 配置分别见 [Spring 外部配置文档](https://docs.spring.io/spring-boot/reference/features/external-config.html) 和 [SSL bundle 文档](https://docs.spring.io/spring-boot/reference/features/ssl.html)。

正式的提交、状态恢复和调度绑定维护入口是现有 XXL-Job Bean handler `devTaskJob`，对应 `DevTaskJob.execute()`：先执行 `DevScheduleCoordinator.maintain()`，再执行 `DevDsSubmitter.processPending()`。业务 Cron 由 DS 执行，XXL 维护器负责同步绑定、导入触发实例及处理提交/停止/恢复，不替代业务 Cron。需要连续运行时，在已配置的 DataForge XXL 执行器中按该 handler 配置维护任务及适当周期；它处理平台全部符合条件的开发任务，应了解已有任务范围后启用。准备脚本不创建、启用或触发 XXL 任务，也不更改现有 XXL 状态。临时按 taskId 范围执行的验收维护器只用于隔离验收，不能替代正式 `devTaskJob` 部署。

准备逻辑的聚焦检查：

```sh
python3 deploy/dolphinscheduler-local/test_prepare_dev_native_runtime.py
```
