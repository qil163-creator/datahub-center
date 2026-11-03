# 数据中台可靠性修复与对账说明

> 历史记录：数据生命周期管理功能已移除，本文有关其归档、销毁、对账页面及调度处理器的说明不再适用于当前代码。已有环境升级前应停用 `governanceLifecyclePolicyJob`、`governanceLifecycleReconcileJob` 和 `bpmLifecycleEventRelayJob`，等待旧执行器退出，再按前置要求执行 [0122 生命周期移除迁移](../sql/postgresql/migrations/0122_governance_remove-data-lifecycle.sql)；不要按下文重新启用这些任务。下文保留当时的迁移与验证记录。

本次对应：交换未知提交、生命周期并发与 UNKNOWN、血缘幂等与版本、开放 API 授权。代码变更不代表已在共享或生产数据库部署。

后续建模存量数据适配新增 0013，当前基线已更新至 0013；其执行分支和验证边界以 [建模旧数据适配说明](modeling-legacy-data-adaptation.md) 为准，下文 0010–0012 的验证记录保留为当时结果。

## 组件与职责

复用现有 DB Switch 的连接、元数据和标识符能力，复用 Spring 本地事务、XXL-Job 与 Redis。领取、提交阶段、血缘水位、对账和授权属于领域不变量，保留在 DataForge / Governance / Supply；没有新建第二套数据库方言或分布式调度框架。

## 升级前置与 SQL 执行顺序

当前迁移目录曾删除历史编号文件并复用编号。本次未改写这些已有脚本，也未重用历史编号，新增 0010–0012。质量门禁仍报告 MIGRATION_ALLOCATION_ORDER：相对 HEAD 期望 0004–0007，而工作树包含用户的 0004 和本次 0010–0012。**必须先核对目标环境迁移执行记录与校验和，再审批升级；不能仅按当前文件名判断历史执行情况。**

已有库必须具有当前 0003 所描述的运行租约、交换检查点、血缘 outbox 结构及生命周期表。先停用相关调度，等待或隔离旧执行器，备份平台库，然后依次执行：

1. `sql/postgresql/migrations/0010_governance_lifecycle-reconcile.sql`：生命周期操作快照、令牌与租约。
2. `sql/postgresql/migrations/0011_governance_lineage-idempotency.sql`：血缘应用水位、租户内事件与关系唯一约束。发现重复关系会整体回滚并阻断；由治理负责人核查后合并，不自动删除。此脚本不能重复执行。
3. `sql/postgresql/migrations/0012_dataforge_exchange-unknown.sql`：中文字段语义及历史 UNKNOWN 隔离。已产生后续执行的历史批次不擅自覆盖最新任务，须另行核查。

新库只导入同步后的 `sql/postgresql/datahub-center.sql`（Included migrations through: 0012），不要再重复执行上述迁移。用户工作树中的 0004 建模迁移属于另一项变更，应按实际环境单独核对，不能跳过其前置状态，也不能用全量基线升级旧库。

## 调度配置

在既有 XXL-Job 管理端检查以下处理器已配置且按平台执行一次；本次未修改共享调度中心：

- `dataforgeExchangeJob`：交换扫描，同时恢复过期执行。
- `governanceLifecycleReconcileJob`：建议每分钟执行，过期或旧版无租约 RUNNING 收敛为 UNKNOWN，不自动重做处置。
- `governanceLineageOutboxRelayJob`：重放待处理事件；持续失败进入人工处置，不能删除事件后盲目重投。

## 交换 UNKNOWN

UNKNOWN 不是普通失败。不要清空 execution_token，也不要直接改成 FAILED 或删除批次。

1. 暂停对应任务，按批次、检查点、目标事务日志核验目标数据。
2. 确认原目标数据库会话已经结束，不会再迟到提交。仅客户端超时、rollback 返回成功或目标行数相等都不单独构成充分证据。
3. 在交付列表“对账”填写结论和证据（10–1000 字）。需要回执与运行两个权限。
4. 确认已提交：原子登记成功并推进增量位点；确认未提交/已回滚：登记失败但不推进位点，允许后续明确重跑。对账接口本身不会写目标库。

如果目标内容无法确定、曾产生部分写入或源数据已变化，应继续保留 UNKNOWN；先完成数据级核验/补偿，不能把“无法确认”当作“未应用”。

## 生命周期 UNKNOWN

归档/销毁列表提供独立对账入口。操作快照为 ARCHIVE、RESTORE 或 DESTROY，新记录不允许更换操作类型。历史记录没有操作快照时，由有权限人员结合日志和远端证据明确归类。

- ARCHIVE：已应用进入 SUCCESS，未应用回 READY。
- RESTORE：已应用进入 RESTORED，未应用回 SUCCESS；需要归档执行权限以及恢复权限。
- DESTROY：已应用进入 SUCCESS 并更新元数据/标签，未应用回 READY 并撤销待删除元数据标识。

必须确认原远端会话结束并提供证据；单纯的表存在/不存在可能受到并发 DDL 影响，需结合目标身份和执行记录。执行令牌失效的迟到响应不能覆盖状态。收尾与本地元数据、审计同事务，但该事务不覆盖远端 RPC。

## 血缘事件

发送方为每个持久运行记录提供稳定 eventId，以任务作为 correlationId，以运行/部署记录编号作为递增 revision。模型部署新信封使用 `model-deploy-v2-` 命名空间，避免与旧 revision=0 快照冲突。

接收方在平台范围内约束事件键唯一，同 eventId 改业务内容拒绝；同聚合低版本不覆盖高版本，相同版本不同业务内容拒绝。图写入与已应用水位在同一事务提交，outbox 成功标志不再是唯一版本依据。JSONB 字段顺序变化不影响内容摘要。旧客户端仍可使用 revision=0，但同一聚合一旦有新内容就必须推进版本，不能长期把 0 当作可覆盖版本。

本次不宣称把所有旧生产者的“首次 RPC 尚未落接收方即失败”场景统一替换成发送方 outbox；接入/推送仍有原有告警型上报路径，需另立任务补齐生产者可靠投递。

## 验证边界

已加入交换故障窗口、生命周期领取/迟到结果/对账、血缘内容与版本、API 授权，以及 PostgreSQL 迁移与锁测试。真实 PostgreSQL 用例仅连接测试容器。当前 Docker daemon 不可用，数据库专属测试被跳过，不能据单测结果宣称生产级数据库行为已验证。

当前前端全量 typecheck 存在多个既有页面错误；质量门禁还有上述历史迁移编号阻断。应先在隔离测试环境启动 PostgreSQL/Redis，补跑数据库测试和多节点故障演练，再发布。

## 本轮实际检查结果（2026-09-15）

使用 Java 25、Maven 离线 reactor（`-pl :<模块> -am`），均添加 `-Dsurefire.failIfNoSpecifiedTests=false`：

| 模块与测试选择 | 结果 |
| --- | --- |
| dataforge-server：ExchangeExecutorTest、DataPushLineageSupportTest | 16 个通过 |
| governance-server：LifecycleReliabilityTest、LineageReliabilityTest | 16 个通过 |
| governance-server：ReliabilityMigrationPostgreSqlTest | 3 个因无 Docker 被跳过，不算通过 |
| supply-server：DataApiGatewayServiceTest、SupplyApiRedisStoreTest、DataApiSecurityBoundaryTest | 14 个通过 |
| modeling-server：ModelDeployServiceImplTest | 25 个通过 |

建模验证过程中补齐原测试缺少的 testcontainers-mysql 测试依赖，并修正嵌套 spy 和可变 Mapper 入参导致的测试夹具误判；未改变生产状态机或弱化断言。

- 前端交换 `data.test.ts`：Vitest 7 个通过；交换、生命周期以及 Supply/Market 本次修改文件专项 ESLint 通过。
- 前端 Supply/Market 专项 oxlint、stylelint 与 `check:antdv-next:boundaries` 通过。
- `pnpm --filter @vben/web-antdv-next run typecheck`：退出码 2，仍存在未修改的交换表单等页面类型错误；本次修改的交换列表/对账与生命周期文件未再出现类型错误。直接调用 vue-tsc 曾因默认堆上限退出，已改用项目 8GB 参数完成上述检查。
- `python tools/quality/run_all.py`：退出码 1；错误码、模块依赖通过，迁移仅剩 MIGRATION_ALLOCATION_ORDER 阻断，未绕过门禁。
- `git diff --check` 无空白错误；三个迁移的内容均已包含在全量基线，基线标记唯一且为 0012。
- 未执行全量前端生产构建、真实多节点/Redis 故障演练、完整新库基线导入或共享数据库升级。前端类型错误及数据库测试环境/迁移账本问题尚未解决，不能宣称已完成发布验收。
