# DataX PostgreSQL 幂等写入与故障恢复验收

日期：2026-11-02（Asia/Shanghai）。在已有隔离策略验收环境中新增独立测试表和作业，通过真实 DataX → SQL 混合作业验证 Worker 故障、宿主虚拟机故障及完整重放。测试未修改运行表伪造状态，未清空旧重复数据。

## 结果

| 场景 | 平台记录 / DS 父实例 | 实际故障及恢复 | 最终结果 |
| --- | --- | --- | --- |
| Worker 中断 | 61 / 84 | 前两批共 2,000 行已提交；下一批被目标行锁阻塞时对独立 Worker 执行 SIGKILL；确认旧 PG 连接退出且未提交批次回滚，启动 Worker 后自动恢复 | 同一父实例成功，DataX 和开发 SQL 都成功；Worker 执行由 99 变为 100 |
| 宿主中断 | 62 / 87 | 3,007 行已提交、DataX 仍为 RUNNING 时强制停止并重启 `job-policy` Colima 虚拟机，实际中断 PostgreSQL、Master 和 Worker | 同一父实例自动恢复成功，DataX 和开发 SQL 都成功；Worker 执行由 104 变为 106 |
| Worker 场景完整重放 | 63 / 90 | 新请求重新执行完整混合作业 | 成功，目标行数不增加 |
| 宿主场景完整重放 | 64 / 93 | 新请求重新执行完整混合作业 | 成功，目标行数不增加 |

宿主故障指隔离 Linux 虚拟机重启，没有重启物理 Mac。记录 61、62 的故障恢复没有重新调用作业执行接口；63、64 是另外明确发起的完整重放验证。

两个目标最终均为 **3,007 行、3,007 个不同业务键**；与源表双向 `EXCEPT ALL` 差异数为 **0**，金额合计 **31,657,696**，源/目标校验值均为 `164f666ade98dbecdd9a4a04abf3038b`。测试数据包含 NULL、中文、引号和前后空格，并预置一条错误旧值验证更新，不仅验证忽略重复插入。故障前部分提交窗口目标为 2,001 行（2,000 行新数据加 1 行预置值）。

## 修复与能力边界

原 PostgreSQL DataX Writer 使用 INSERT，执行恢复会重新消费源数据；平台请求幂等和调度串行不能防止已经提交的外部数据重复追加。此次新增显式 `DATAX_UPSERT` 任务类型和 `POSTGRESQL_UPSERT_V1` 写入契约，界面可选择“幂等写入（PostgreSQL DataX）”及目标唯一键。

通用唯一索引核验和 SQL 构造归属 DB Switch，提取并复用已有 `PostgresUpsertWriter` 的实现；DS 扩展复用官方任务和 DataX JDBC 类型绑定。每批在同一事务核验目标约束后执行参数化 UPSERT，相同值不重复 UPDATE，失败整批回滚，不采用脏数据吞掉或逐行提交降级。平台仅允许已安装插件的工作组发布该类型，旧 Worker 不会静默按 INSERT 执行。

最初记录 59、60 因实际 DataX 发行依赖的占位符探测调用失败，未写入新数据，开发节点停止。已修复 `calcValueHolder("")` 探测兼容性并补充针对真实 Java 8 / DataX 依赖的契约检查；两次失败及日志完整保留。后续 61–64 均成功。

能力适用于 PostgreSQL 12+ 普通表、完整非空普通唯一键、可重放且同键确定的源数据。需要目标 SELECT/INSERT/UPDATE 权限。不支持用户触发器、RLS、分区表或未验证的扩展类型；不承诺源端删除、CDC 游标、多表或外部副作用的原子性。历史追加业务需将业务批次/版本纳入唯一键。旧追加作业不会自动转换，平台不会自动清表、建约束或清理历史重复。

部署、固定上游源码校验及组件归属见 [运行插件说明](../../yudao-framework/yudao-spring-boot-starter-biz-dolphinscheduler/runtime/postgresql-upsert/README.md)。

## 保留的数据与日志

- 隔离数据库：`codex_job_e2e_20261102_080442_28e9`。
- 源表：`codex_job_e2e_idem_src_20261102_122553`。
- 目标表：`codex_job_e2e_idem_worker_20261102_122553`、`codex_job_e2e_idem_host_20261102_122553`。
- Worker 作业 9：集成任务 1006 → 开发任务 11；宿主作业 10：集成任务 1007 → 开发任务 12。
- 原周期、等待、丢弃、抢占、并行测试目标保留 6 / 15 / 6 / 12 / 6 行，共 45 行，原重复记录也保留。
- 作业 4–10 均为 `scheduleStatus=0`、`nextRunTime=null`，活动数和排队数均为 0。完成验收后同时关闭隔离队列扫描器 group 3 / job 4。
- 平台记录 59–64 均已终态。查看日志与下载日志逐字节一致；另归档故障前原生 Worker 尝试和恢复后执行日志。

公开证据在 [verification/idempotent-20261102](verification/idempotent-20261102/)，包括 `policy-execution.json`、`final-state.json`、原始尝试日志、平台父子日志、构建及测试输出。`manifest.json` 记录归档文件 SHA-256。私密配置和应用控制台未进入归档。任务、测试数据、DS 日志持久卷均保留。

## 验证

- 后端定向测试 39 项通过、0 跳过，包含真实 PostgreSQL 的重放、回滚、更新、唯一约束拒绝、参数及混合作业测试。
- 使用实际 DS 3.4.2 / DataX 依赖的 Java 8 `RuntimeContractTest` 通过。
- 后端 `python3 tools/quality/run_all.py` 通过。
- 前端 `pnpm run quality:antdv-next` 通过：420 个测试文件、3,693 项测试，lint、typecheck、生产构建均通过。
- 浏览器已确认记录 61、62 显示成功，日志查看及下载正常。`ui-verification.json` 保留两项通过证据，同时如实记录后续配置页组合框定位超时，因此浏览器整套检查不标记通过。配置保存/唯一键校验的组件测试通过；配置页最终截图未完成，未纳入归档。

按用户在另一会话下达的开发环境清理安排，随后停止测试 DataForge 50027、Vite 6003、预览代理 6006 和 `job-policy` Colima 虚拟机，不再自动启动。正常 Docker DolphinScheduler 12345 及用户 IDE 服务未停止或改动。关闭服务不删除持久数据；运行态验收证据均在停机前取得。

故障验收入口为 `prepare_idempotent.py` 和 `idempotent_smoke.py`。新故障场景必须使用独立新 fixture；不能在已经完整写入的旧目标上伪造“部分提交”窗口。完整重放模式可用于保留数据的重复执行验证。验收脚本在发起任何请求前排他锁定共享证据账本，避免并发验收进程覆盖彼此证据。

## SQL 执行顺序

本次不新增平台迁移。隔离平台库已完成以下前置迁移，本次没有重复执行：

1. `sql/postgresql/migrations/0256_dataforge_job-native-children.sql`
2. `sql/postgresql/migrations/0257_dataforge_job-child-record-states.sql`
3. `sql/postgresql/migrations/0258_dataforge_job-durable-scheduling.sql`

本次测试表 DDL 仅由 `deploy/job-policy-local/prepare_idempotent.py` 在限定的既有 `codex_job_e2e_*` 隔离数据库执行：创建独立源/目标表及主键 → 插入测试数据 → 授权既有测试角色 → 通过正式接口创建任务和作业。它不是平台升级迁移，不应对业务库执行。
