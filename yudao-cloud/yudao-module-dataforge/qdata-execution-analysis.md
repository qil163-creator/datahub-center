# qData 数据集成与执行拓扑分析

本文记录对 `https://pro-demo.qdata.tech/` 的登录态页面观测结果，以及本项目 DataForge 后端的实现映射。观测使用 REA 浏览器运行时和网络事件；账号、密码、验证码和访问令牌没有写入仓库。

## 观测到的页面与接口

| qData 页面 | 页面任务类型 | 主要查询接口 | 页面能力 |
| --- | ---: | --- | --- |
| 数据连接 | 项目授权数据源 | `/da/dataSource/dpp/list`、`/da/dataSource/list` | 数据源列表、测试连接、详情、更多 |
| 数据集成-离线任务 | `type=2`、`processType=2` | `/dpp/etlTask/getDppEtlTaskPage`、`/dpp/etlTask/statistics` | 类目树、运行状态、调度策略、配置/详情/更多 |
| 数据集成-实时任务 | `type=2`、`processType=1` | 同上，另有运行实例查询 | Flink 实时流处理、运行控制 |
| 批量文件入库 | `type=7` | `/dpp/etlTask/getDppEtlTaskPage`、`/dpp/etlTaskInstance/statistics` | 文件任务、实例和日志 |
| 整库同步 | `type=5` | `/dpp/etlTask/getDppEtlTaskPage`、`/da/dataSource/list` | 源库到目标库、同步表数量、调度策略 |
| 数据开发-离线/实时 | `type=3`、`processType=2/1` | `/dpp/etlTask/getDppEtlTaskPage`、`/dpp/etlTask/statistics` | Spark SQL 离线、Flink 实时、调度和配置 |
| 作业管理 | `type=4` | `/dpp/etlTask/getDppEtlTaskPage`、`/att/jobCat/list` | 集成任务与开发任务的跨任务依赖编排 |
| 运维管理 | `taskType=1/3/5/4` | `/dpp/etlTaskInstance/list`、`/treeList`、`/statistics` | 实例状态、详情、日志下载 |

上述页面还会读取 `dpp_etl_task_status`、`dpp_etl_node_instance`、`dpp_etl_node_type`、`dpp_etl_task_execution_type`、`dpp_etl_task_instance_command_type`、`dpp_etl_task_priority` 等字典，以及项目类目接口 `/att/taskCat/list`、`/att/dataDevCat/list` 和 `/att/jobCat/list`。作业管理首次请求缺少 `projectId` 时，页面会出现必填参数错误，说明项目上下文是后端查询边界的一部分。

在离线任务详情中，页面会请求 `/dpp/etlTask/updateQuery/{id}` 并展示版本、上线状态、串行/并行策略、Cron、项目、责任人和 DAG。观测到的流程是“表输入组件 → 表输出组件”，节点 `taskParams` 包含字段映射、查询条件、读写数据源、批量大小、写入模式和前后置 SQL，边关系通过 `taskRelationJson` 保存。该响应的 `datasourceConfig` 含加密密码，平台实现详情回显时必须只返回脱敏配置，不能把原始配置写入日志或响应。

新增任务弹窗还明确把“执行引擎”和“调度器”分开：引擎可选 DataX、Spark、Flink；Spark 有 Driver/Executor 资源和 Yarn 队列；调度器可选 DolphinScheduler 或 Quartz，并配置 Cron、Worker 分组、优先级、重试、延迟和串行策略。因此“页面存在 DolphinScheduler 字段”只能证明它是一个可选控制面，不能证明所有 DataX、文件入库或 ETL 节点都由它执行。

整库同步新增页不是普通弹窗，而是四步向导：基础信息（任务、责任人、源库和目标库）、选择来源表、多表映射、属性配置。页面初始化读取 `/da/dataSource/list`，提交前要求来源表加载成功、至少选择一张表并通过映射/附加字段校验；随后把 `readerDatasource`、`writerDatasource`、`tables`、`addColumn` 和单条根任务关系组装到 `taskDefinitionList`，节点类型为 `ALL_DB_SYNC`，节点执行类型为 DataX，并调用整库任务创建/更新接口。该行为与本项目离线整库路径的 DB Switch 执行器相对应，不能因为 qData 表单默认把节点写成 DataX 就推断平台必须直连 DataX 集群。

本项目的 ETL 注册表已经覆盖对应的组件边界：数据库、Kafka、REST、Excel、CSV、JSON 输入；数据库、Kafka、Excel、CSV、JSON、文本输出；字段映射、过滤、清洗、加解密、合并、脱敏、条件、派生、常量、行列转换、排序、拆分、主键、计算、值映射、质量检查、数值范围、展开、连接、笛卡尔积、记录生成和随机记录转换；Shell、Python、SQL、JavaScript 脚本及子流程边界节点。流程保存时执行 DAG、端口和参数校验，运行时由有界本地执行器按拓扑顺序调度节点，并为每个节点记录状态和日志。

流程编辑页的静态资源还暴露了更完整的 qData 组件库：表/ODPS/Excel/CSV/JSONL/Kafka/Hive/HDFS/API/MongoDB/Redis/RabbitMQ/TDengine/RocketMQ/FTP/WebService 输入，Java 脚本和自定义 token，转换、Join、排序、字段拆分/派生/去重、行列转换、加解密、ID、计算、值映射、常量、范围、字符串、字段选择、设置值，条件分发、子流程，以及表/ODPS/Hive/HDFS/MongoDB/Redis/RabbitMQ/Kafka/RocketMQ/FTP 输出。qData 通过 `/dpp/etlComponetVerify/componet` 做组件级校验；本项目对应使用 ETL `/validate`、`EtlExecutorRegistry` 和各节点执行器，未知工具码在创建执行器时拒绝。

qData 流程保存接口包括 `/dpp/etlTask/createProcessDefinitionEx`、`/dpp/etlTask/updateProcessDefinition`、`/dpp/etlTask/updateEtlTask`、`/dpp/etlTask/compile/check` 和 `/dpp/etlTask/updateQuery/{id}`，运行入口为 `/dpp/etlTask/startDppEtlTask/{id}`。本项目对应的保存、发布、校验、运行、停止和节点日志接口集中在 `EtlPipelineController`。qData 的调度器选项包含 Quartz，但演示环境将 Quartz 置为不可选；本项目对集成流程统一采用受控的 XXL-Job 扫描和本地 ETL 执行，未把不可验证的 Quartz 语义伪装成已实现能力。

表输入/输出抽屉的配置也已实测：输入支持数据连接或 SQL、表/视图、全量或 ID/时间增量、where、分批读取和字段属性；输出支持目标表、字段映射、前后置 SQL、追加/全量/增量更新和单次写入条数。相关数据源与表元数据来自 `/da/dataSource/tableList/{datasourceId}` 和 `/da/dataSource/list`。qData 的“任务检查”在观测中主要由前端做连线、输入输出和同源同表检查；本项目把同类检查放到后端 `/dataforge/etl-pipeline/validate`，运行前再次校验，不能只信任浏览器检查结果。

调度周期弹窗是纯前端 Cron 生成器，支持秒到年、周期/指定/起止模式，并展示最近五次运行时间；本项目仍会在服务端校验 Cron，且提供 `/dataforge/etl-pipeline/cron-preview`、`/dataforge/dev-task/cron-preview` 等预览接口，避免客户端生成的表达式绕过后端校验。

## 执行引擎结论

页面文案只能证明它支持 Flink、Spark SQL 和调度策略，不能单独证明所有任务都由 DolphinScheduler 执行。结合页面行为、网络接口和本项目后端代码，当前可靠结论如下：

| 任务 | 实际执行器 | 调度/控制面 | 结论依据 |
| --- | --- | --- | --- |
| 离线整库、全量、增量同步 | DB Switch | XXL-Job 扫描与恢复 | `DataAccessExecutor`、`MigrationService` 和数据同步运行记录 |
| 实时同步 | SeaTunnel | SeaTunnel REST | `SeatunnelRestApi` 提交/停止作业；不经过 DolphinScheduler |
| API 接入 | 平台 API 接入执行器 | XXL-Job | `DataAccessExecutor` 的 API 分支 |
| SQL、Shell、Flink、Spark、PyFlink 开发任务 | DolphinScheduler | DolphinScheduler | `DevDsSubmitter` 创建、发布、触发工作流 |
| 可视化 ETL 画布 | 平台本地 DAG 执行器 | XXL-Job 扫描与触发 | `EtlPipelineExecutor`、有界线程池和节点记录 |
| 集成工作台任务 | 绑定的平台 ETL 流程 | XXL-Job | `IntegrationTaskService` 只负责任务控制和流程绑定 |

因此，qData 页面上的“作业管理”应理解为跨任务依赖编排层；在本项目中，开发任务依赖快照由 DolphinScheduler 工作流承载，而同步和可视化 ETL 的执行仍由各自执行器完成。当前 DataForge 集成工作台已经保存 DataX/Spark/Flink 的资源配置，但绑定流程后的执行委托仍是平台 ETL 流程，尚未把 DataX 任务直接提交到外部 DS。不能把 XXL-Job 误认为业务执行引擎：它负责扫描、触发、恢复和导入实例。

## 本次后端实现

新增只读接口 `/dataforge/execution-topology/catalog` 和 `/dataforge/execution-topology/resolve?taskKind=...`。接口受 `dataforge:ops-instance:query` 权限保护，供 qData 风格的详情、运行控制和运维弹窗显示实际执行边界。支持的任务类型编码为：

`DATA_ACCESS_OFFLINE`、`DATA_ACCESS_REALTIME`、`DATA_ACCESS_API`、`BATCH_FILE_IMPORT`、`DEVELOPMENT_SQL`、`DEVELOPMENT_NATIVE`、`ETL_PIPELINE`、`INTEGRATION_TASK`、`JOB_ORCHESTRATION`。

该接口只输出已存在的执行器能力，不会凭空创建调度任务，也不会改变已有任务的运行路径；已有实现继续复用 `DbSwitchRuntime`、`MigrationService`、`SeatunnelRestApi`、`DolphinSchedulerClient`、`EtlPipelineExecutor` 和 `OpsInstanceService`。

REA 运行证据：`ev_e23a5c56bf4938e332da5ca4c1dcf5bee2cf3d8929d26f45dc9cd048c79b3b42`、`ev_e65552638adf5917c96f59b0b58d1e40f19b741ad6b96320c7ad604e9ced45a8`、`ev_1ebc7a49044102bfc56d5954ea669e7721b58e5e633a8fd7baec30b2c91c7a7c`。
