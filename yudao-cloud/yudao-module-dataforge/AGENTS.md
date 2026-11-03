# DataForge 模块约束

本文件适用于 `yudao-module-dataforge/**`，与仓库根 `AGENTS.md` 同时生效。

- DataForge 负责平台数据源记录、驱动、同步/ETL、填报、SQL 探索和数据推送领域状态；跨模块合同放在 `dataforge-api`，实现放在 `dataforge-server`。
- 元数据发现、数据库产品/方言、标识符、类型、DDL、reader/writer 和运行时全量/增量搬数必须先检索 DB Switch。业务代码只保留数据源记录解析、领域配置映射、权限、任务状态、调度、重试和编排。
- 后台任务按单平台执行，不要求租户参数、上下文恢复或 `@TenantJob`/`@TenantIgnore`；重型任务不得默认依赖 `parallelStream` 或无显式 executor 的 common pool。
- durable outbox/inbox 只能复用经过当前场景验证的实现；不得把某一填报子域的实现当成平台通用组件。必须覆盖抢占 lease、发送中崩溃恢复、最大重试、死信/人工处置、对账和历史清理。
- 文件通过 infra API/现有协议处理；`byte[]` 上传设置明确大小上限。预签名或流式大文件必须把对象写入和平台元数据登记作为两阶段结果，处理登记失败、补偿和孤儿清理。
- 跨服务调用在事务外部失败时必须保留可恢复状态；不要在数据库锁事务中执行长时间数据库搬数、BPM、文件、HTTP 或 MQ 调用。
- DataForge 连接器向用户配置的外部 MQ/数据库发送数据，不等于平台内部消息总线；两类边界、凭据、幂等和观测不得混用。
