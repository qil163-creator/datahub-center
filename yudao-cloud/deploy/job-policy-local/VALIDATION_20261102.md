# 混合作业周期、串行策略与并行保护验收

验收完成于 2026-11-02，时间均为 Asia/Shanghai。使用真实 PostgreSQL、DataX、DolphinScheduler SQL Worker、平台认证及 XXL-Job 回调。数据研发原有导航保留，测试作业和运维页面可从 [本机预览](http://127.0.0.1:6006/develop/job) 查看。

## 最终结果

| 场景 | 真实记录与结果 | 数据与并发核对 |
| --- | --- | --- |
| 周期触发 | 作业 4；10、11 成功；12 关闭周期后取消 | 实际计划时间 08:41:30、08:42:00，`SCHEDULE`；没有人工 run 代替触发；6 行、sum 62；12 无 DS/Worker |
| 串行等待 | 作业 5；7 → 8 → 9 按 FIFO 成功 | 父子均成功，相邻真实父实例无重叠；SQL 累计 9/12/15 行；同 requestId 只返回原 8，只有一个 DS 父实例 |
| 忙时丢弃 | 作业 6；13 成功，14 丢弃，空闲后 15 成功 | 14 无 DS/Worker，不中断 13；6 行、sum 62 |
| 优先抢占 | 作业 7；52 停止，53 被取代，54 成功 | 旧数据库 writer PID 在解锁前消失，未用 `pg_terminate_backend`；53 无 DS/Worker；54 在旧父真实退出后开始，只新增 3 行 |
| 并行资源保护 | 作业 8；55/56 首轮，57/58 修复复验 | 55、57 均完整成功；56、58 拒绝且无 DS/Worker，不停止首个请求；58 明确显示“任务正在运行中，请稍后再试”；两轮共 6 行、sum 62 |

并行验收覆盖“同一开发任务仍保持单活”的保护行为，不声称同一开发任务支持多个实例同时执行。每个完整成功的混合作业都核验了独立的 DataX 与 SQL 子实例、真实 Worker、冻结定义/项目身份、父子关联、依赖先后及日志。该组合验收不等同于对 Flink、Spark、Shell 或存储过程所有执行路径的再次验收。

核心证据：[执行证明](verification/20261102/policy-execution.json)、[历史记录复核](verification/20261102/historical-revalidation.json)、[最终状态](verification/20261102/final-state.json)、[25 条作业日志索引](verification/20261102/all-job-logs.json)。日志索引包含文件名、SHA-256、字节数、查看/下载一致性以及实际秘密值扫描结果。

## 本轮发现并修复

1. **恢复后实例无法结束**：宿主重启导致 DS 为同一子实例保留两个父任务关联，其反向查询返回 `10119 / TooManyResultsException`。平台因此保留运行占位。复用了现有 DS API，在业务侧仅针对已持久完成双向核验的同一子实例，进一步验证当前有效父任务和原父任务都仍指向它，才允许恢复对账；未绑定、关联变化、普通网络失败仍拒绝放行。恢复前后的锚点不会被覆盖。实例 21 已真实收敛为 SUCCESS，日志明确记录恢复验证过程。[修复前](verification/20261102/reboot-incomplete-21.json)、[修复后](verification/20261102/reboot-record-21-recovered.json)、[原实例日志](verification/20261102/record-21-job.log)。
2. **并行拒绝提示不清楚**：原记录 56 只显示 `DuplicateKeyException`。在已有任务行锁内、幂等请求命中之后，复用 `DevDsSubmitter.isRunning` 检查单活，返回已有 `DEV_TASK_RUNNING` 业务错误；数据库唯一索引未移除。新记录 58 的页面、日志和下载均显示可理解的占用原因，原 56 保留不改写。
3. **调度器正常关闭**：隔离镜像改为 `exec` JVM，以 Java PID 1 接收 SIGTERM。无活动实例且业务周期关闭时进行了一次正常关闭/重建，原 token、项目、全部实例/任务状态与目标数据相同，`WorkflowSerialCoordinator-Thread` 存在；没有删除 registry 锁或修改运行表。[重启验证](verification/20261102/scheduler-graceful-restart.json)。

组件归属：运行身份与已验证关联属于作业领域；恢复适配复用 `WorkflowInstanceApi`，未新增 HTTP 客户端或直接访问 DS 元数据库的生产代码。开发并发保护复用已有 `isRunning` 和错误码，不放宽原生权限、冻结目标或单活索引。

## 如实保留的历史与数据

宿主在前轮验收后重启，`/tmp` 中的临时过程文件和截图已丢失。持久 PostgreSQL、DS 日志卷、源码和原 UI 元数据仍在；本轮从真实实例重新导出日志、复核历史成功记录并生成新截图，未把丢失的临时截图列为当前可用附件。

- 4：首次 DataX 环境缺少 Java PATH，失败且零写入；环境修复后保留原失败记录。
- 5、6：各有一次 DataX 写入，但旧 SQL 子流程未完整成功，最终 STOPPED。它们解释了 WAIT 目标的额外 6 行，不计为完整作业成功。
- 16/17/18：第一轮抢占竞态；17 在被替换前已进入派发阶段，最终 CANCELLED，不能当作 QUEUED → SUPERSEDED。18 成功。
- 19/20/21：第二轮真实停止和排队替换；21 在宿主中断后由 DS 故障恢复。DataX 的追加目标在恢复时多写入一份 3 行；该数据未删除。后续 52/53/54 在保留它的基础上完成了严格的抢占、进程退出和只新增三行验证。
- 56：并行单活保护已生效，但旧提示为异常类名；修复后追加 57/58 验证，旧记录保留。

**外部追加写入仍是至少一次语义。** 平台请求幂等和串行占位不能使 DataX 跨宿主/Worker 故障变为 exactly-once。本轮修复了恢复对账与拒绝提示，没有把重复追加描述为已解决；需要恰好一次的业务写入应另行使用目标端业务唯一键、幂等落库或事务式装载方案。

| 目标 | 最终行数 | amount 合计 | 来源副本说明 |
| --- | ---: | ---: | --- |
| cron | 6 | 62 | 两次完整定时作业 |
| wait | 15 | 155 | 三次完整成功 + 两次已停止的部分写入 |
| discard | 6 | 62 | 两次完整成功 |
| priority | 12 | 124 | 18、21、54 完整成功 + 21 故障恢复的一份重复追加 |
| parallel | 6 | 62 | 两轮首请求成功，两个第二请求均未写入 |

共保留 25 条真实父实例，包含 12 条 SUCCESS、3 条 FAILURE、10 条 STOPPED；没有把决策拒绝、未执行或部分写入记录改成成功。三行源值和每行副本数都通过 SQL 多重集核验，总计 45 行、sum 465。

## 页面与关闭状态

[页面验收](verification/20261102/final-ui.json) 无 page error、HTTP/业务错误或业务写请求，覆盖实际优先抢占/拒绝标签、日志搜索及下载、`SCHEDULE → 定时调度` 标签。空闲周期开启时活动 0、排队 0、下次执行时间可见，并实际观察到两次后续自动刷新。

空闲刷新检查只临时设置未来的 23:00，不冒充真实触发验收。检查后恢复原 `0/30 * * * * ?` 表达式并关闭周期；跨过下一 30 秒边界，作业 4 的记录仍为原 3 条、目标仍为 6 行。[关闭证明](verification/20261102/cron-final-closed.json)。作业 4–8 最终均 `scheduleStatus=0`、`nextRunTime=null`、活动 0、排队 0。XXL 隔离协调器保持开启，仅用于后续手动执行的队列推进，不产生已关闭的业务周期。

新环境保留 `.local/share/datahub-job-policy-runtime/work/` 的私密配置与运行快照、Colima 持久卷、全部测试表和日志。浏览器临时认证文件在验收后删除。主业务库和原 DS 不是这些新增用例的写入目标；本报告证明隔离实例及当前源码，不将旧 H2 调度器在宿主重启前的观测冒充当前运行状态。

## 验证命令与结果

- Java 25：`mvn -q -pl :yudao-module-dataforge-server -am -Dtest=DevDsRecoveryTest,DevJobExecutionTest,JobManagedRunServiceTest,JobServiceTest,JobRuntimeBoundaryTest,JobStateBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test`：68 项通过、0 跳过。[明细](verification/20261102/final-backend-test-results.json)。恢复正向用例曾在原实现上复现失败；修复后的真实实例 21 同样通过。
- `python3 tools/quality/run_all.py`：迁移、错误码、模块边界门禁通过。[输出](verification/20261102/backend-quality.log)。
- Node 24.16.0：`pnpm run quality:antdv-next`：边界、lint、typecheck、420 文件 / 3692 项测试及构建通过；lint 保留原有 79 条 warning、0 error。[输出](verification/20261102/frontend-quality.log)。宿主安装的 pnpm 是原生可执行文件，使用源码外 Node 启动包装清除 `npm_execpath`，未修改门禁或源码规避检查。
- 真实数据库/引擎与 UI 场景见上方证据；修复并行占用提示后重新实际执行 57/58，重新截图、下载日志。没有用 Mockito 代替这部分真实验收。

## SQL 执行顺序

本轮修复无新增 SQL。前序周期能力依赖以下有序迁移，目标为已有 PostgreSQL 平台库的 `datahub-center` schema：

1. `sql/postgresql/migrations/0256_dataforge_job-native-children.sql`。
2. `sql/postgresql/migrations/0257_dataforge_job-child-record-states.sql`。
3. `sql/postgresql/migrations/0258_dataforge_job-durable-scheduling.sql`：前两项已执行后应用；先停旧版本作业写入口。前序已在原配置库执行并核对数据不变；当前隔离库使用包含 0258 的基线。不得向已有库重放全量基线替代升级。
