# 混合作业真实策略验收

本目录使用独立 PostgreSQL、DolphinScheduler 3.4.2 和平台实例，验证真实 DataX → SQL 混合作业。平台持久队列负责周期槽与并发策略；XXL-Job 只调用 `jobManagementCoordinatorJob`。所有受理、停止、调度开关和日志读取使用正式接口，不修改运行表伪造状态。

最终结果见 [2026-11-02 完整验收报告](VALIDATION_20261102.md)。原始证据、日志与截图在 [verification/20261102](verification/20261102/)。

后续 PostgreSQL DataX 幂等写入及 Worker / 宿主恢复结果见 [幂等恢复验收报告](IDEMPOTENT_VALIDATION_20261102.md)，公开证据在 [verification/idempotent-20261102](verification/idempotent-20261102/)。该能力需显式选择幂等模式和唯一键，原追加验收记录保持不变。

## 当前保留环境

2026-11-02 验收后按用户的开发环境清理安排，已关闭业务测试周期、隔离 XXL 扫描器 group 3 / job 4，停止测试 DataForge、Vite、预览代理和 `job-policy` Colima 虚拟机。以下为保留的配置地址，不代表当前正在提供服务。持久卷、测试表、实例及日志保留；不要自动重启占用用户开发端口。

| 用途 | 本机端点 / 标识 |
| --- | --- |
| 可查看的项目页面 | http://127.0.0.1:6006/develop/job |
| 作业运维实例 | http://127.0.0.1:6006/develop/operations/job |
| 隔离 DataForge | 127.0.0.1:50027；XXL executor 10027 |
| 隔离 DS | 127.0.0.1:12347；Docker context `colima-job-policy` |
| 隔离 PostgreSQL | 127.0.0.1:55427 |
| 平台测试库 | `codex_job_policy_platform_20261102`，schema `datahub-center` |
| 测试库（旧追加、新幂等） | `codex_job_e2e_20261102_080442_28e9` |
| 周期 / 等待 / 丢弃 / 抢占 / 并行作业 | 4 / 5 / 6 / 7 / 8 |
| 隔离队列扫描器 | XXL group 3 / job 4，原每 5 秒；验收后已停止 |

私密运行配置及不可变 classpath 快照位于用户目录 `.local/share/datahub-job-policy-runtime/work/`，不进入版本库。新的验收输出也保存在该持久目录，不依赖重启后会清空的 `/tmp`。页面沿用项目原有导航；数据研发请求经本机预览代理到隔离实例，登录和权限仍由真实 System 服务校验。

## 执行方式

读取现有配置继续验收时，先确认对应作业无未结束实例、业务周期关闭。脚本保留 requestId，未知结果不得换 requestId 盲目重投；发现既有数据时不会清表。

```sh
python3 deploy/job-policy-local/job_policy_smoke.py \
  --config "$JOB_POLICY_PRIVATE_CONFIG" \
  --output-dir "$JOB_POLICY_EVIDENCE_DIR" \
  --case parallel-retained --attempt newcheck
```

`priority-retained` 在既有数据上新增 A/B/C：真实目标锁阻塞 A，B/C 连续受理，确认 B 被替换、A 的数据库写入连接退出后，才释放 C 的测试锁。它需要环境变量 `XXL_POLICY_ADMIN_USER`、`XXL_POLICY_ADMIN_PASSWORD`，只允许暂停并恢复隔离 group 3 / job 4；两个请求之间不能打开同一作业页面或读取其实例，以免页面对账改变被测排队窗口。暂停只用于稳定观察该窗口，不伪造业务队列或运行状态。

`parallel-retained` 保留已有数据，验证共享开发任务的第二个请求被明确拒绝，首个请求完整成功，只新增三行。`wait`、`cron`、`discard`、`priority`、`parallel` 原始场景要求对应空白新 fixture；它们不是已有数据的清理或重置工具。`wait-recovery`、`priority-burst` 是保留的历史故障复现过程，包含原始记录身份约束，不应作为新环境通用入口。

创建新 fixture 使用 `prepare_fixture.py` → `prepare_connections.py` → `prepare_tasks.py`，输出目录和私密配置必须属于新隔离环境；已有配置会被拒绝覆盖。配置文件权限要求 0600。源固定三行，目标允许重复来源 ID，以便用真实 count、sum 和多重集核对每一次追加；读取和开发 SQL 使用只读账户，写入账户仅有目标表 SELECT/INSERT。

## 保留与恢复

Compose 的 `policy-pg-data` 和 `policy-ds-logs` 是持久卷。不要执行 `down -v`，不要删除测试表、历史实例、日志或重建它们来使验收通过。新镜像使用 `exec` 启动 JVM，使其作为 PID 1 接收正常停止信号；仅在业务周期关闭且所有真实父子实例终态后维护调度器。

DataX 的追加写入不提供跨 Worker/宿主故障的 exactly-once 语义。DS 恢复可能重新执行已部分写入的任务；本次实际出现的一份重复三行保留在数据和报告中。平台请求幂等、串行占位和停止确认不等于外部数据写入恰好一次。

现有 PostgreSQL 平台库需先完成 `0256_dataforge_job-native-children.sql`、`0257_dataforge_job-child-record-states.sql`，再执行 `0258_dataforge_job-durable-scheduling.sql`。迁移前停止旧版本作业写入口；新库使用已含 0258 的基线。本轮补充验收的修复没有新增数据库结构变更。
