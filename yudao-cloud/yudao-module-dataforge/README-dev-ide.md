# 数据开发 IDE 增量改造记录

## 第一批：任务定义、草稿一致性、发布快照与实例日志

本批在现有 DataForge、DolphinScheduler starter 和 Vue 工作台上修改，保留目录、租户、权限、草稿 revision、发布版本、运行幂等键、冻结依赖、lease 和 UNKNOWN 核查流程。

六种任务形态均已进入任务定义与配置表单：`FLINK_SQL`、`FLINK_JAR`、`PYFLINK`、`SPARK_SQL`、`SPARK_JAR`、`PYSPARK`。本批不将“可以保存定义”作为“已能原生运行”的证据。

| 响应 executionCapability | 含义 | 本批行为 |
| --- | --- | --- |
| STANDARD | 既有 SQL、存储过程、Shell | 继续使用现有调试/发布限制及调度路径 |
| LEGACY_SQL | 没有 programConfig 的历史 Flink/Spark SQL | 保留原有调度器 SQL 通道；不宣称是原生应用提交 |
| DRAFT_ONLY | JAR/Python，或设置了 programConfig 的引擎 SQL | 保存草稿；发布和执行器提交均拒绝误入 JDBC/SQL 通道 |

运行与调度使用已发布的不可变快照。当前草稿新增原生配置，不影响继续运行既有发布版本。能力字段是本批代码支持状态，不能代替对实际集群版本、插件及访问授权的探测。

`programConfig` 为可空对象，结构版本为 1，包含批/流模式、部署档案代码、引擎版本、入口、制品文件编号及 SHA256、依赖编号、参数和引擎专属资源配置。现阶段部署档案和文件编号只保存为草稿引用，后续接入执行前必须核实存在性、租户/对象授权、制品完整性与兼容性。不得将其当作已验证的资源或把凭据写入配置。

工作台将脚本、数据源、超时、最大行数一起保存和判断未保存状态；保存期间继续编辑的内容保留。外部表单保存、回滚后的刷新只覆盖本地未编辑的字段。任务信息表单维护程序配置，移除已被后端禁止的直接调度器数据源编号输入。

发布快照新增 `schemaVersion=1`，所有读取统一经过校验，兼容历史省略版本，拒绝未知版本、非对象和尾随 JSON。回滚恢复完整可编辑配置，包括脚本、类型、数据库、程序配置、质量任务、依赖、超时、行数、说明和 Cron；保持当前发布指针、调度状态及经授权执行身份，并使用 revision 防止覆盖并发编辑。数据库展示信息重新从受管数据源解析，清除历史裸 DS 数据源编号。

## 日志接口与组件复用

新增 `GET /dataforge/dev-record/task-log?id=<平台实例编号>&skipLineNum=0&limit=200`，沿用 `dataforge:dev-task:query` 和租户内工作区可见范围。通过平台记录的项目/工作流定位唯一调度节点；拒绝裸外部编号、关联不一致、多节点歧义和缺少可信运行标记的历史实例。

响应包含 `message`、`readLines`、`nextSkipLineNum`、`mayHaveMore`、`truncated`、`available`、`source`、`statusMessage`。客户端使用返回游标；活跃运行的空页不表示结束。组件限制每页最多 1000 行、128 KiB，剥离 DS 首次查询的合成路径/主机头，按实际文件行计算游标。超长单行可能截断并明确标记，下一次从后续行读取。已知凭据、认证字段和 JDBC 连接串所在行按开发日志脱敏规则遮蔽；任意用户日志的全面敏感数据治理仍依赖执行侧日志策略。

本批技术归属检索与结论：

- DolphinScheduler starter 已有 `LogApi`、`TaskInstanceApi` 和 HTTP 客户端；分页规范化属于通用协议处理，扩展组件公开 API，保留原始 API。DataForge 只负责平台实例归属、权限、状态和脱敏策略。
- 开发记录和运维实例服务各自通过预编译正则执行所属领域的日志脱敏规则，不依赖已移除的日志采集功能。
- DB Switch 已有元数据、方言与执行入口；本批继续使用现有 JDBC 调试和元数据路径，不复制 SQL 解析器或数据库机制。
- `DevProgramConfig`、任务类型/能力判定和快照版本属于 DataForge 领域，留在业务模块。文件继续沿用 infra 文件编号契约，本批不建立另一套上传或存储组件。

日志协议依据 [DolphinScheduler 3.4.2 LoggerServiceImpl](https://github.com/apache/dolphinscheduler/blob/3.4.2/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/service/impl/LoggerServiceImpl.java) 和 [TaskInstanceController](https://github.com/apache/dolphinscheduler/blob/3.4.2/dolphinscheduler-api/src/main/java/org/apache/dolphinscheduler/api/controller/TaskInstanceController.java) 核对；仍须在当前部署的准确版本上联调。

## SQL 执行顺序

1. 已有库：确认编号迁移已按部署账本应用至 `0120_modeling_single-target.sql` 且研发任务表存在，再执行 `sql/postgresql/migrations/0121_dataforge_dev-program-config.sql`。新增可空 JSONB 列和对象约束，不回填历史配置或改写发布/运行快照。
2. 新库：使用已同步至 `0121` 的 `sql/postgresql/datahub-center.sql`；不需要重复应用 `0121`。

先升级数据库再部署本批应用。脚本使用锁超时，重复执行可重放；回退应用时保留新增列，删除列会丢失草稿配置。本批没有操作现网数据库或提交集群任务。

## 验证与下一批

新增和扩展测试覆盖草稿清空、完整回滚及 revision、六种形态的分类和执行拦截、历史快照兼容、文件编号 JSON 契约、日志边界/归属/脱敏、真实 PostgreSQL 迁移首次执行和重放。前端回归覆盖完整草稿快照、保存竞态、原生配置切换和接口契约。

2026 年 10 月 9 日已执行的验证：

- 后端定向 Java 编译及 JUnit 共 61 项通过、0 跳过：快照/真实 PostgreSQL 7 项，任务定义/发布/恢复/依赖 31 项，日志组件/业务接口/客户端自动装配 23 项。
- 前端 `pnpm run quality:antdv-next` 完整通过：活动应用 149 个测试文件、1606 项通过、0 跳过；全应用类型检查、边界检查、本批 ESLint 检查和实际生产构建通过。另有本批 4 个目标测试文件、42 项回归全部通过；ESLint 的测试桩警告不阻断门禁。
- `python tools/quality/run_all.py` 与本批 `git diff --check` 通过。迁移、错误码和模块边界检查没有新增违规，历史债务按原门禁规则处理；最后迁移编号为 `0121`。

当前工作区已有 POM/BOM 调整，reactor 在模型解析时缺少依赖版本；单模块构建还受内部快照依赖及插件缓存影响。本批使用 Java 25、本机缓存依赖定向编译和 JUnit 运行受影响用例，不能据此宣称当前 Maven 构建或实际集群验收通过。合并部署前仍须在有效 POM 下完成组件及 DataForge 消费模块构建、前后端联调和真实部署验证。

后续沿用本批模型继续实现：

1. 只读核实 Nacos 中生效的 DS、Flink/Spark 版本、运行方式、Worker/租户和资源约定，建立可验证的部署档案与操作能力。
2. 接通受管制品选择、上传/登记补偿与依赖解析，并在现有 DS 集成中逐项接入六种任务的原生提交、状态、取消和失败恢复。只有相应适配器及集群验证通过后，才开放对应原生发布能力。
3. 在现有工作台接入 Flink SQL Gateway / Spark 会话协议、结果分页和执行计划；补齐 Savepoint/Checkpoint、Spark 应用与流查询运维。

六种原生执行形态全部完成并经实际环境验收后，才算完成双引擎改造整体目标。
