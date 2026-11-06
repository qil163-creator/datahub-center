# XXL-Job 调度任务清单

本清单适用于各微服务分别部署的方式。执行器列填写 XXL-Job 的 AppName，与对应服务的 `spring.application.name` 一致；运行模式使用 `BEAN`，JobHandler 填写表中的原文。表内 Cron 是建议在调度中心配置的周期，并不代表当前环境已经创建或启用了这些任务。部分周期来自 Job 源码注释，其余沿用运维建议；上线前仍需结合实际处理量确认。

| 执行器 | JobHandler | 任务用途 | Cron |
| --- | --- | --- | --- |
| `system-server` | `tokenCleanJob` | 清理过期令牌 | `0 10 3 * * ?` |
| `infra-server` | `accessLogCleanJob` | 清理访问日志 | `0 20 3 * * ?` |
| `infra-server` | `errorLogCleanJob` | 清理错误日志 | `0 30 3 * * ?` |
| `infra-server` | `managedFileCleanupJob` | 有界扫描 5 分钟前受管文件删除墓碑，重新清理晚到对象；存储操作在锁事务之外，失败保留后续重试 | 建议 `0 * * * * ?` |
| `dataforge-server` | `dataAccessSyncJob` | 数据接入排期、派发、恢复和对账 | `0 * * * * ?` |
| `dataforge-server` | `batchFileRecoveryJob` | 批量文件入库持久排队派发、失效租约转未知及文件回收；未知实例不自动重放，手动执行环境也须启用 | 建议 `0 * * * * ?` |
| `dataforge-server` | `databaseMonitorCollectJob` | 单平台串行采集连接、响应时间及支持产品的已用容量；每批最多 100 个候选且 30 秒后不再发起采样；分钟槽幂等、采集租约恢复、35 天样本有界清理；评估连接及响应时间告警 | 建议 `0 * * * * ?` |
| `dataforge-server` | `etlPipelineJob` | ETL 排期、派发、租约恢复；质量报告、目标提交/质检和回放复检对账；持久积压指标采样 | `0 * * * * ?` |
| `dataforge-server` | `etlFileRecoveryJob` | 单平台扫描已知文件意图后逐项核验执行人；失租约且文件锁可取得后核对同对象，并处理显式申请的到期本人发布文件及锚点清理，默认KEEP不按年龄删除；最多20项/次、10次自动重试，未知身份转人工；不重跑图、不强制覆盖目标 | 建议 `0 * * * * ?` |
| `dataforge-server` | `jobManagementCoordinatorJob` | 作业平台 Quartz 槽位原子受理、三串行策略队列与真实父子恢复；到期和队列阶段各至多二十作业、每作业五槽或五次派发，单轮对账每作业至多十实例，每作业最多一百待结束请求；未知触发不重投 | 建议 `0/5 * * * * ?` |
| `dataforge-server` | `devTaskJob` | DS 周期绑定同步、已触发实例导入及运行恢复（不计算业务 Cron） | `0 * * * * ?` |
| `dataforge-server` | `fillTaskGenerateJob` | 生成在线填报期次任务 | `0 * * * * ?` |
| `dataforge-server` | `fillOutboxRelayJob` | 在线填报事件投递 | `0 * * * * ?` |
| `dataforge-server` | `fillMergeJob` | 在线填报合并执行及重试 | `0 * * * * ?` |
| `dataforge-server` | `fillOverdueJob` | 在线填报逾期扫描 | `0 0/5 * * * ?` |
| `dataforge-server` | `fillRemindJob` | 在线填报截止提醒 | `0 0/30 * * * ?` |
| `dataforge-server` | `fillBpmReconcileJob` | 在线填报审批状态对账 | `0 0/30 * * * ?` |
| `governance-server` | `metaCollectTaskJob` | 单平台采集排期、持久队列消费和失联租约恢复；执行前核验执行人权限，手动模式也需启用以恢复等待实例 | 建议 `0 * * * * ?` |
| `governance-server` | `governanceQualityTaskJob` | 质量任务及模板扫描 | `0 * * * * ?` |
| `governance-server` | `qualityRunRecoveryJob` | 待执行质检恢复 | `0 * * * * ?` |
| `governance-server` | `governanceSafetyStaticTaskJob` | 全局扫描静态脱敏任务，逐项核验执行人后执行 | `0 * * * * ?` |
| `governance-server` | `masterDistributeJob` | 主数据派发持久等待恢复、发送后过期转未知、原子受理周期槽位；手动/一次性环境也须启用 | 建议 `0 * * * * ?` |
| `governance-server` | `masterDistributeAlertRelayJob` | 主数据未知/领取耗尽告警持久投递、历史补账、过期接管及死信提示；不重跑业务 | 建议 `0 * * * * ?` |
| `governance-server` | `masterDistributeAlertDispositionJob` | 死信人工重投或接管，要求受控参数及调度审计；不解除业务 UNKNOWN | 不配置 Cron，仅人工触发 |
| `governance-server` | `masterDistributeAlertCleanJob` | 有界清理 90 天前已投递且无原日志/人工审计的告警，保留未知/死信 | 建议 `0 45 3 * * ?` |
| `governance-server` | `qualityWorkOrderRecheckJob` | 质量工单复检 | `0 0/5 * * * ?` |
| `governance-server` | `qualityWorkOrderNotifyJob` | 质量工单通知派发；按工单原子认领，发送租约过期归为 UNKNOWN，禁止自动重发未知结果 | `0 0/5 * * * ?` |
| `asset-server` | `marketApplyExpireJob` | 集市申请到期及交付重试 | `0 0 * * * ?` |
| `asset-server` | `assetCatalogReconcileJob` | 单平台重试目录挂接回执与停供撤权，失败进入人工处置；撤权实际交付由 marketApplyExpireJob 继续处理 | 建议 `0 * * * * ?` |
| `supply-server` | `supplyGrantReconcileJob` | 授权操作状态对账 | `0 0/5 * * * ?` |
| `supply-server` | `supplyApiOperationReconcileJob` | 单平台有界扫描失联开放写操作，转为UNKNOWN待核查，不重试SQL | 建议 `0 * * * * ?` |
| `supply-server` | `supplyQuotaExpiryJob` | 单平台释放过期读请求额度预占，最多1000条；旧工作者无法确认或返回数据 | 建议 `0 * * * * ?` |
| `ai-server` | `aiSunoSyncJob` | Suno 生成状态同步 | `0 * * * * ?` |
| `ai-server` | `aiMidjourneySyncJob` | Midjourney 绘图状态同步 | `0 * * * * ?` |

共 37 个生产调度任务。`demoJob` 仅用于示例，不在生产环境创建。AI 服务的两个任务仅在部署并使用相关能力时启用。

这些扫描类 Job 的 Cron 控制扫描频率；被扫描的业务任务仍按其自身配置的 Cron 或到期时间执行。启动调度前，确认服务的 `xxl.job.enabled`、`xxl.job.admin.addresses` 和通讯 Token 与实际调度中心匹配，并在执行器显示在线机器后验证手动触发及回调。


数据开发任务的业务 Cron 使用 DolphinScheduler 的 Quartz 规则（周日为 1、周六为 7）。保存任务只保存可运行版本；执行一次由用户单独发起，周期只在显式开启调度后登记到 DS。`devTaskJob` 保留为维护入口，不再扫描任务 `next_run_time` 或在本地解释业务周期。维护入口使用单平台控制面扫描并核验业务对象，且必须持续启用，否则 DS 已触发的标记实例不会及时导入平台运行链路。

启用数据开发周期前，配置 DolphinScheduler 客户端连接及受管项目 `dataforge.dev.ds-project-code`；周期标记使用 `dataforge.dev.schedule.worker-group` 和 `dataforge.dev.schedule.tenant-code`（默认回退已有 `dataforge.dev.worker-group`、`dataforge.dev.tenant-code`），要求专用 Worker 组及非 root 执行租户。原生任务的业务程序仍使用保存时冻结的受管环境及项目，周期标记本身只运行固定 `exit 0`。`dataforge.dev.schedule.timezone-id` 指定业务周期时区（默认 `Asia/Shanghai`）；`dataforge.dev.schedule.api-timezone-id` 必须与 DS API 的无偏移日期和日期查询时区一致（默认 `Asia/Shanghai`），不能从浏览器时区推断。周期登记的日期边界使用毫秒时间戳，避免 JSON 日期在不同服务器时区漂移。

调度绑定记录先持久化为待同步，再由维护任务进行 DS 定义和 Schedule 对账；旧代确认停用后才启用新代，远端创建响应未知时通过精确名称、项目、代次和定义标记恢复。自动失败重试最多 5 次，界面保留错误摘要；修复 DS 或执行授权后，重新保存任务或调整调度会重试旧代停用和当前代登记，关闭周期不依赖已撤销的历史执行身份。迁移 `0164` 会备份旧 Spring Cron、关闭旧周期并清空活动表达式；需在界面按 Quartz 规则重新配置后开启，历史快照和运行记录保留原文。

每次认领最多读取最新 100 个 DS 实例以及一个持久历史分页；只导入真正的 `SCHEDULER` 非试运行成功标记，运行键为项目与 DS 实例编号，业务日期来自 DS 的实际调度时间。实例列表排序包含可变结束时间，因此编号水位仅供诊断，历史周期完成后重新扫描并通过持久运行键去重，迟到或重排的实例不会被编号水位永久跳过。历史对账成本随实例数量增长；最新第一页独立导入，正常新触发延迟取决于维护频率与外部请求耗时。默认建议每分钟维护；实际吞吐量需结合 DS 请求时延及积压调整。

作业管理的业务 Cron 使用 `DolphinCronSchedule` 的 Quartz 规则与 `Asia/Shanghai` 时区，由 `jobManagementCoordinatorJob` 按持久游标受理；DolphinScheduler 只运行每次专属的 `PARALLEL` 父子工作流。开启或更新平台周期前先确认旧 DS 直接业务周期已停用；停止周期取消该代未派发槽位，已经触发的真实运行继续对账。三串行策略在同一作业数据库行锁下受理：等待按顺序排队，抛弃记录真实拒绝审计，优先保留最新排队并等待旧运行父子真实退出。队列领取失联不等于引擎停止；未知触发及准备链保留占位，不自动重新投递。PARALLEL 也不能绕过开发任务本身的单活资源护栏。

本地验收覆盖使用独立执行器：主预览 `50026` 的 AppName 为 `dataforge-job-local-50026`、端口 `10026`；隔离验收 `50027` 使用 `dataforge-job-e2e-50027`、端口 `10027` 及独立平台库/DS。两者必须各自注册 `jobManagementCoordinatorJob`，不能混入旧 `10024` 执行器组；现有监控任务保持原组。周期配置与运行记录保留，验收业务周期完成后关闭；协调器须持续在线以恢复队列与未知实例。
