# 统一模型精简、迁移与验证说明

当前应用以“设计数据表、ER 关系、发布和变更”为主线。已有库的唯一新入口是 **`sql/postgresql/migrations/0131_modeling_simplify-design-deploy.sql`**；完整执行一份脚本即可，不再拼接 0123—0130 的片段。旧脚本视为已交付历史，保留原文件，不再修改。

## 本轮最终结构

| 保留对象 | 职责 |
| --- | --- |
| `dh_modeling_logic_model`、`dh_modeling_logic_model_version` | 唯一设计及不可变版本，列表/字段表单/ER 共用 |
| `dh_modeling_logic_relation` | 表之间的业务关联与稳定字段映射，不等于加工血缘 |
| `dh_modeling_physical_model`、`dh_modeling_physical_model_version` | 设计的实际目标绑定、引擎参数、冻结发布产物；不重复维护业务字段定义 |
| `dh_modeling_deploy_record` | 发布计划、DDL 执行与核验、实际结构基线、变更证据和登记状态 |
| `dh_modeling_data_domain`、`dh_modeling_dw_layer`、`dh_modeling_subject_domain` | 数据域、数仓分层、可选分析主题 |
| `dh_modeling_biz_category` | 业务分类目录、页面与维护接口继续保留 |
| `dh_modeling_planning_change` | 保留规划变更的审计记录 |

共 11 张业务表。另保留 7 张已有迁移证据表，它们不提供业务维护入口。本次证据放入已有 `dh_modeling_unification_state.migration_evidence`，没有新增独立业务表。

删除概念模型/实体/关系/版本/同步映射/同步记录 6 表、旧主题 3 表、多环境目标表、后台巡检 3 表、主题跨数据域说明表、独立实库基线表，共 15 项旧表。`dh_modeling_biz_theme` 在部分环境已删除，使用 `IF EXISTS` 兼容。可选分析主题 `subject_domain` 本身继续保留。

## 应用行为

- 一份模型定义维护业务含义、粒度、字段、维度/指标和 ER 关系；发布固化设计版本，目标配置生成对应部署产物。设计变更与实库部署状态分别显示。
- 移除概念建模、概念生成/同步、旧独立逻辑/物理编辑器、后台巡检任务与配置、多环境配置及对应菜单/权限。旧概念 URL 不会按相同 ID 跳到统一模型。
- 保留即时设计校验、标准门禁、部署前实库观察、变更差异和 DDL 预览。ER 保存的业务关联不自动变成强制数据库外键。
- 部署成功时，将完整实库字段 JSON 写入 `deploy_record.observed_schema_snapshot`，与成功状态、模型部署版本和固定治理登记载荷在同一本地事务提交。该事务失败时保留 UNKNOWN，核验后可恢复，不重复执行外部 DDL。
- 下次发布优先比较成功记录内的结构快照；旧记录无快照时兼容原结构摘要。两者都缺失、快照损坏或实际结构漂移时禁止继续部署，页面给出不可执行原因。
- 后台巡检已移除；部署未知结果核验及成功后元数据/血缘登记的持久补偿仍保留，避免外部操作已完成而平台永远缺记录。
- 历史目标 JSON 只用于恢复原部署证据，不查询已删除环境表。旧 HTTP `targetId` 参数明确拒绝，新客户端不提交。
- 现有变更执行能力为建表、增列和结构核验；删列、改名、不兼容类型变更仍在计划中明确阻断，不能以新增空列冒充数据迁移。

历史部署核验要求原数据源 ID 的 JDBC 地址和 schema 身份保持不变。数据源中心当前仅在停用时盘点阻断引用，仍可通过更新启用中的连接改变同一 ID 的目标；本轮未改动该跨模块既有行为。若历史目标已被改指，先恢复原连接再核验，不能把另一库的同名同结构表当作原部署结果。

本轮继续复用 DB Switch 的元数据、类型/DDL 计划与执行能力，以及现有模型比较器、MyBatis 和事务组件。新增内容只是部署领域的快照保存和历史证据解码，没有新增数据库方言或重复技术执行器。

## SQL 执行顺序

1. **已有库**：停止旧建模应用及相关消费者，完整执行 `sql/postgresql/migrations/0131_modeling_simplify-design-deploy.sql`，成功后启动本轮前后端。它包含必要的前置恢复，已完成阶段自动跳过；不要先单独执行 0130，也不要在 0131 成功后回放更早脚本。
2. **新空库**：执行 `sql/postgresql/datahub-center.sql`，其首行最高编号必须为 0131；不需要再执行测试清空脚本或历史升级链。

```powershell
psql "$env:DATAHUB_MIGRATION_DSN" -X -v ON_ERROR_STOP=1 -f sql/postgresql/migrations/0131_modeling_simplify-design-deploy.sql
if ($LASTEXITCODE -ne 0) { throw '0131 未提交，读取首个 ERROR；不要选段继续执行' }
```

SQL 固定平台 schema `"datahub-center"`，不访问或删除外部业务数据源的实际数据表。首条 `ROLLBACK` 用于结束此前失败的事务，随后本轮所有变更在一个新事务执行。数据库客户端须在独立连接整文件运行，遇错停止。提交后的删表删列不可逆，恢复须使用窗口前外部备份及匹配代码；迁移归档用于核对，不等同于完整回滚脚本。

脚本接受达到 0122 的完整旧结构及后续合法阶段。缺少准备账本且旧表已删时，仅在设计头、两类版本、关系和映射均为空，且没有矛盾的后续账本证据时，走单独的空设计恢复分支；孤儿部署只允许终态且没有待登记/租约，原部署记录保留，孤儿基线完整归档；该分支登记真实的 `EMPTY_MODEL_FINALIZATION_V1`，不伪造旧阶段。

旧基线按租户、模型、数据库、表名、版本号准确匹配唯一成功部署。原始基线全部保存到本轮迁移证据；无法证明关联的基线不冒充成功部署快照。有效目标如果因此失去可验证的运行基线，或已存在快照与旧基线冲突，整笔回滚并明确报出对象。正在执行/未知的部署及未完成的可靠登记必须先处理，不能猜造成功结果。旧应用停写后，已授权退役的后台巡检记录随本次精简清理，陈旧的排队/运行状态不再阻断删除。

迁移后核对：

```sql
SET search_path = "datahub-center", pg_catalog;
SELECT migration_key, phase,
       migration_evidence->'baselineMergeSummary' AS baseline_merge
FROM dh_modeling_unification_state
WHERE migration_key IN ('MODEL_SIMPLIFICATION_V1','EMPTY_MODEL_FINALIZATION_V1');
SELECT id, model_id, version_no, status, baseline_status, baseline_saved_at,
       observed_schema_snapshot IS NOT NULL AS has_observed_snapshot
FROM dh_modeling_deploy_record
ORDER BY id;
```

`MODEL_SIMPLIFICATION_V1` 必须为 `CLEANED`。历史记录是否有快照取决于真实关联证据，不要求批量填满。再验证新建模型、保存 ER、发布设计、预览 DDL、部署成功及再次增列变更；同时验证旧入口和权限不再可用。

## 本轮验证结果（2026-10-10）

- 后端：建模模块完整 Maven 测试通过，43 个测试类、274 例，0 失败、0 错误、0 跳过。覆盖业务分类、统一草稿/ER/发布权限、部署快照事务一致性、历史目标核验、引用盘点和版本不可变性；数据库用例使用真实 PostgreSQL 18、本地 Testcontainers PostgreSQL 16 与 MySQL 8.4。
- SQL：`verify_modeling_simplification.py` 在独立 PostgreSQL 18 测试库完成 14 组迁移验证。包含有数据升级、缺失准备恢复、空设计且残留部署/基线、分类数据和菜单授权保留、失败整体回滚、新库最终结构及重复执行。迁移后 18 张建模表（11 业务、7 证据）。
- 前端：完整 `quality:antdv-next` 通过边界检查、ESLint/stylelint、Vue 类型检查、145 个测试文件中的 1561 例测试以及生产构建（11/11 任务成功）。保留 7 个既有测试桩 lint 警告及依赖构建警告。
- 仓库门禁：`python tools/quality/run_all.py` 通过 DB Switch 架构、100 份 SQL、1060 个错误码和模块边界检查；本轮补丁 `git diff --check` 通过。
- 操作手册：Markdown/HTML 内容一致，已在浏览器以 1440/390 宽度验证目录、搜索、打印和溢出。
- 未执行：没有连接或修改用户实际数据库，也没有在浏览器连接真实后端执行完整发布流程；本轮验证在隔离测试数据库及自动化测试中完成。现场切换后仍按上方核对步骤验证页面与实际目标数据源。

本次完整迁移文件 SHA-256：`EE2968380996739CB6FACA1CF09ABCB71FC61688B841A7969127B7698D2C9C57`。

## 历史迁移说明（0131 之前，仅供追溯）

以下保留先前审查、错误修复和验证记录。它们描述旧阶段，不代表当前入口或当前执行顺序；当前精简库不要照此回放旧脚本。

## 交付文件与前置条件

所有 SQL 路径相对于后端仓库。平台数据库模式固定为 `"datahub-center"`；这些脚本不访问业务目标数据源，不搬运数仓数据。

| 文件 | 内容 | 执行对象与前提 |
| --- | --- | --- |
| `sql/postgresql/migrations/0123_modeling_unified-definition.sql` | 增加 revision、关系快照、精确设计版本关联；完整备份和映射；独立物理模型回填设计草稿；审查函数 | 已有库已达到 0122，所有建模写入来源已停止 |
| `sql/postgresql/migrations/0124_modeling_unified-target-cleanup.sql` | 校验审查、漂移、授权和未决动作；建立目标绑定约束；删除重复列；修复触发器 | 0123 完成，逐对象确认，配套新版制品已在副本验收，保持停写 |
| `sql/postgresql/migrations/0125_modeling_unified-menu.sql` | 复用逻辑模型菜单为统一入口，隐藏旧菜单，保存前后镜像 | 0124 完成，角色菜单覆盖已由管理员核对，保持停写 |
| `sql/postgresql/migrations/0126_modeling_remove-workspace.sql` | 归档后退役协作/审批/报告/收件箱/分发/回执，补布局修订与活动目标唯一索引 | 0125 完成，旧实例/任务停写，旧审批和 ACTIVE 建模引用变更已正式结束 |
| `sql/postgresql/migrations/0129_modeling_consolidated-upgrade.sql` | 一份独立脚本接续完成 0124—0127，兼容 0128 清空已执行后的状态 | 0123 已成功，旧应用与任务停止；不重复清空模型 |
| `sql/postgresql/migrations/0130_modeling_resume-missing-preparation.sql` | 缺少准备完成记录时实际补做完整 0123，并包含 0129 的全部接续步骤 | 前置旧结构达到 0122；允许只完成清空或只提交了部分准备结构；旧应用与任务停止 |
| `sql/postgresql/datahub-center.sql` | 包含至 0130 的最终结构；恢复脚本不改变已完成统一库的最终结构 | 仅空库；新库不执行测试清空，需核对最终标记与配套应用 |

已有环境应以实际发布账本核对前置迁移，重点包括 0079 的分层、0080 的发布菜单、0112 的归属与审批冻结、0113 的协作工作区、0120 的单目标约束和 0122 的治理清理。文件存在不证明环境已经执行；也不应盲目重跑历史全链。

环境账本记录每个脚本编号及 SHA-256。0123、0124、0125、0126 均在独立事务中执行，锁等待超过 10 秒会失败；全量 JSONB 备份会占用额外空间，先在生产规模副本评估窗口。序列在事务失败时可能留空号，不应重置以填补空号。

## 正式切换流程

1. 在副本完成代码改造、迁移和端到端验收，准备新版制品；先通过正式角色管理处理旧概念、物理或检测入口独立授权但缺少逻辑入口授权的角色。SQL 不自动补授新权限。
2. 停止全部旧实例及建模相关后台写入，包括部署、检测、审批回调与补偿消费；等待活动业务完成，保留窗口前外部数据库备份。不要先迁移生产、再用数天时间开发新代码。
3. 执行 0123。全程保持停写，导出映射和完整差异，逐对象审查。
4. 执行审查确认函数；必须保留真实审查人、具体原因及审查的内容摘要。0123 只准备证据，不默认确认。
5. 明确声明切换条件并执行 0124、0125、0126。新版应用在清理后启动，完成冒烟再恢复任务消费与用户写入。
6. 核对迁移账本、角色入口、历史链接、部署结果与治理引用，留存 SQL 哈希和执行日志。

从 0123 开始至 0126 完成必须持续停写。0123 对既有映射不重复创建，也不刷新已保存内容；若有旧页面或任务在窗口中写入，0124 会因头、版本或 ACL 漂移拒绝。中止切换，恢复窗口前备份并纠正写入来源后重新演练。不得删除账本、改摘要或批量将状态改为 CONFIRMED 来绕过。

0123 的失败会整体回滚，0124 的失败也会整体回滚本阶段；0124 已成功而 0125 失败时保持停写，修复明确的菜单前置问题后只重试 0125。不要重跑已经 CLEANED 的 0123。

## 审查数据和确认方法

在迁移数据库中执行下面的只读查询，检查每个映射，而不仅是统计条数。完整 `original_*` 和 `canonical_*` 列保留头、所有版本及协作策略/成员，供审核及归档使用。

```sql
SET search_path = "datahub-center", pg_catalog;
SELECT tenant_id, physical_model_id, logic_model_id, migration_kind,
       review_status, review_issues,
       md5(original_head::text || original_versions::text) AS physical_digest,
       md5(canonical_head::text || canonical_versions::text) AS design_digest
FROM dh_modeling_unification_map
ORDER BY tenant_id, physical_model_id;
```

| 问题标识 | 审查含义 |
| --- | --- |
| `NEW_DESIGN_DRAFT` | 独立物理模型已生成设计草稿，核对字段 UID、类型、标准和权限继承；草稿并未发布 |
| `DESIGN_DIVERGENCE` | 已关联两边内容不同，核对最终保留的设计及只读归档内容；不能把确认理解为二者相同 |
| `UNKNOWN_VERSION_SOURCE` | 旧物理版本不能证明精确来源，继续保留空关联，不按最新逻辑版本猜测 |
| `MISSING_HISTORICAL_SNAPSHOT` | 历史发布缺少完整快照，只读展示缺失证据，不使用当前头拼成伪历史 |
| `MISSING_LATEST_VERSION` | 删除历史可能缺失版本；有效模型缺失最新有效版本会在准备阶段直接拒绝 |
| `INVALID_OR_MISSING_FIELDS`、`MISSING_OR_DUPLICATE_FIELD_UID` | 记录缺项；后续设计修复前不能重新发布，更不能猜造历史 UID |
| `INDEPENDENT_ACL_REQUIRED` | 0123/0124 阶段设计与目标授权不同，必须分别保留并核对；0126 后按产品决策切换 RBAC 与租户范围，原 ACL 作为证据保留 |

确认函数为 `dh_modeling_confirm_unification(bigint,bigint,jsonb)`，默认撤销 PUBLIC 执行权，由迁移角色调用。函数不使用 SECURITY DEFINER，不提升调用者权限。`reviewed_by` 记录真实数据库会话身份，JSON 的 `reviewer` 记录业务审核人。

每项确认 JSON 必须包含以下键：

| 键 | 值 |
| --- | --- |
| `decision` | `KEEP_LOGIC_ARCHIVE_PHYSICAL` |
| `aclDecision` | `KEEP_INDEPENDENT_ACL` |
| `reviewer` | 实际审核人 |
| `reason` | 至少 10 字符的具体审核理由，说明差异处理及保留内容 |
| `physicalDigest` | 上述查询返回的对应物理内容摘要 |
| `designDigest` | 上述查询返回的对应设计内容摘要 |

摘要只绑定本次审查的内容，不是安全签名或业务审批。应将完整证据一并归档；不能用示例理由批量自动确认全部记录。执行方式如下，参数由本次真实审查结果提供：

```sql
-- psql 变量 tenant_id、physical_id、review_json 由迁移人员提供。
SELECT "datahub-center".dh_modeling_confirm_unification(
    :'tenant_id'::bigint, :'physical_id'::bigint, :'review_json'::jsonb);
```

确认后证据不可覆盖。若业务决定不接受“保留逻辑定义、归档旧物理差异”，应停止迁移，在旧系统完成设计整理并重新制作窗口备份；本脚本没有自动择优合并字段的分支。已有权限不做并集，确认也不会授予角色或数据源权限。

## 执行命令

在后端仓库根目录运行 PowerShell；连接信息使用环境变量 `DATAHUB_MIGRATION_DSN`，由环境安全注入，不写入仓库或交付日志。命令启用 `ON_ERROR_STOP`，任一失败立即停止。

```powershell
Get-FileHash sql/postgresql/migrations/0123_modeling_unified-definition.sql -Algorithm SHA256
Get-FileHash sql/postgresql/migrations/0124_modeling_unified-target-cleanup.sql -Algorithm SHA256
Get-FileHash sql/postgresql/migrations/0125_modeling_unified-menu.sql -Algorithm SHA256
Get-FileHash sql/postgresql/migrations/0126_modeling_remove-workspace.sql -Algorithm SHA256

psql "$env:DATAHUB_MIGRATION_DSN" -X -v ON_ERROR_STOP=1 -f sql/postgresql/migrations/0123_modeling_unified-definition.sql
if ($LASTEXITCODE -ne 0) { throw '0123 失败，停止切换' }
```

完成前述逐项审查后，0124 与 0125 的执行会话均必须声明就绪契约。这个开关只是防止误执行的操作门禁，无法探测前后端是否真的已验收。先核对部署制品与当前代码、0126 最终结构匹配，并完成环境副本验收，再进入切换。

```powershell
psql "$env:DATAHUB_MIGRATION_DSN" -X -v ON_ERROR_STOP=1 -c "SET datahub.modeling_unified_cutover='UNIFIED_MODEL_V1'" -f sql/postgresql/migrations/0124_modeling_unified-target-cleanup.sql -f sql/postgresql/migrations/0125_modeling_unified-menu.sql
if ($LASTEXITCODE -ne 0) { throw '切换中止，保持停写并检查已完成阶段' }
psql "$env:DATAHUB_MIGRATION_DSN" -X -v ON_ERROR_STOP=1 -f sql/postgresql/migrations/0126_modeling_remove-workspace.sql
if ($LASTEXITCODE -ne 0) { throw '0126 中止，保持停写并处理退役前置冲突' }
```

确认最终状态：

```sql
SET search_path = "datahub-center", pg_catalog;
SELECT migration_key, phase, operator_name FROM dh_modeling_unification_state;
SELECT review_status, count(*) FROM dh_modeling_unification_map GROUP BY review_status;
SELECT count(*) AS switched_menus FROM dh_modeling_unified_menu_backup;
SELECT definition_origin, count(*) FROM dh_modeling_physical_model_version
GROUP BY definition_origin;
SELECT count(*) AS missing_binding FROM dh_modeling_physical_model WHERE logic_model_id IS NULL;
```

预期 UNIFIED_MODEL_V1 与 WORKSPACE_REMOVAL_V1 阶段均为 CLEANED；已有物理映射均为 CONFIRMED；菜单镜像为 5 项；绑定缺失数为 0。新库没有历史模型时映射与版本分类统计可以为空。SQL 完成不代替 UI、对象权限、发布和任务消费冒烟。

## 删除、保留及恢复

0124 删除物理头的重复业务列，保留原头的完整 JSON 证据；旧版本、真实草稿、部署日志、实库基线和治理引用保留。概念业务说明可在高级入口编辑，历史版本继续只读。0125 复用并隐藏旧菜单身份，0126 再归档并删除退役审批等七表，以及协作、审批、收件箱三个按钮权限和对应角色/套餐关联；其余角色授权保留。

不提供“执行清理后直接回退旧代码”的操作。正式回退需要窗口前数据库备份及匹配的旧应用，同时处理窗口后新增写入；外部数仓表的 DDL 不会随平台数据库恢复而自动撤销。归档 JSON 用于诊断与编写后续编号修复，不能代替经过恢复演练的备份。

## 新库示例数据调整

原始基线内有效物理模型 1 指向已删除的逻辑模型 2。准备脚本严格拒绝该状态；不能为了让演示库通过而放宽引用检查。新基线只移除以下已核对的示例 INSERT，不会通过增量迁移删除已有库中的同编号业务记录。

| 新库不再装载的表 | 示例 ID |
| --- | --- |
| `dh_modeling_concept_model`、`dh_modeling_concept_model_version` | 各 1、2、3 |
| `dh_modeling_concept_entity` | 1、2、3、4 |
| `dh_modeling_concept_relation`、`dh_modeling_concept_logic_sync`、`dh_modeling_concept_sync_record` | 各 1 |
| `dh_modeling_logic_model` | 1、2、3、4 |
| `dh_modeling_logic_model_version` | 1、2、3、4、6、7 |
| `dh_modeling_physical_model`、`dh_modeling_schema_baseline` | 各 1、2、3 |
| `dh_modeling_physical_model_version` | 1、2、3、4 |
| `dh_modeling_deploy_record` | 1、2 |
| `dh_modeling_model_check` | 1 |
| `dh_asset_data_query_file` | 1，SQL 直接查询示例物理表 `ads_cesdf` |
| `dh_dataforge_dev_task` | 2，SQL 直接查询同一示例物理表 |
| `dh_dataforge_dev_task_version` | 2、3，均属于上述任务 2 |

本段记录截至 0126 的历史基线调整：移除 36 行建模示例及 4 行直接关联示例；当时保留分层、业务分类、业务过程、数据域、主题域及其规划关联，以及默认检测配置和查询文件目录。后续业务过程/业务对象删除属于并行 0127 任务，以其独立说明及验证为准。此次示例清理没有删除仅名称相似但无引用证据的标准元素或服务 API，也没有重置主键序列。

## 上一轮仅 SQL 交付的验证记录（截至 0125）

验证日期：2026 年 10 月 10 日。使用本机全新临时 PostgreSQL 18 实例，仅监听 `127.0.0.1:15437`，所有成功和失败路径均在专用测试库或其克隆中执行；未连接共享/生产业务库，未使用 H2。

| 实际检查 | 结果 |
| --- | --- |
| 改造前完整基线导入 | `ON_ERROR_STOP=1`，退出 0，保留为不可变测试模板 |
| 原样旧基线执行 0123 | 按预期拒绝“物理引用已删除逻辑”，未绕过或伪造修复 |
| 可复现 0123/0124 验证脚本 | 12 组检查通过，退出 0；含真实脚本首次执行及重放、审查门禁、权限继承、历史保真、名称与租户冲突、检测/登记门禁、不可变守卫 |
| 0125 独立菜单检查 | 7 项通过：缺声明、缺阶段表、PREPARED、角色授权缺口均拒绝；首次切换保留菜单身份和角色授权；重放稳定；人工修改不覆盖 |
| 已实际执行 0123/0124 的成功夹具继续执行 0125 两次 | 两次退出 0，全链和菜单重放完成 |
| 修改后的完整新库基线导入 | 在新建空库执行完整文件，退出 0；阶段 CLEANED、菜单镜像 5 项、业务模型 0 条；分层 11 条、业务过程 228 条保留 |
| 已有库升级与新库初始化的最终结构对比 | `pg_dump --schema-only --no-owner --no-privileges`，只移除随机 restrict 标记后，完整结构文本及 SHA-256 相同 |
| `python tools/quality/run_all.py` | 退出 0；DB Switch、迁移、错误码、模块边界全部通过，工具按当前 HEAD 接受既有错误码/边界历史债务 |
| `git diff --check` | 通过 |

可复现的 0123/0124 检查脚本和 SQL 夹具位于 `yudao-module-modeling/yudao-module-modeling-server/src/test/resources/modeling-unified/`，使用方法见该目录 README。它以改造前基线为模板，创建随机命名的隔离副本，并明确构造有效链接与受限 ACL；合成夹具不作为真实业务数据修复建议。批量审查测试代码不能用于生产自动批准。

上述是上一轮仅文档与 SQL 交付的历史记录，该轮未修改应用代码，因而没有运行 Maven 或前端检查。当前轮已实施应用改造，其编译、单测、前端检查和真实 PostgreSQL 结果在后续当前轮验收记录中单独列出；不能沿用历史未执行说明作为当前结论。生产规模锁等待、备份容量、页面联调和实际目标引擎 DDL 仍需按明确验证范围评估。

## 0126 退役执行与边界

本节记录 0126 历史阶段的执行条件。0145 退役数据库管理的“引用影响”和“数据源引用控制”后，不再提供下述引用盘点、控制台账及回执协议；已有库按实际迁移账本执行 0145，新库使用已包含该清理的全量基线。

SQL 执行顺序：已有库在实际账本达到 0125 后只执行 `sql/postgresql/migrations/0126_modeling_remove-workspace.sql`；尚未统一的库先按上文完成 0123 → 审查 → 0124 → 0125，再执行 0126。新库仅导入 `sql/postgresql/datahub-center.sql`，不重复执行其已包含的迁移。

执行前停止旧建模全部实例、审批任务 `modelingPublicationApprovalJob` 和对象通知任务 `modelingBusinessObjectChangeDeliveryJob`。任务注册若保存在外部 XXL-Job 管理库，由部署人员在该平台停用；本仓库迁移不直接修改外部任务或 BPM 表。未终结审批通过旧应用与 BPM 正式撤回/对账，不得直接批量改成通过或已消费。`dh_dataforge_reference_mutation` 中 owner=modeling 的 ACTIVE 记录须先通过原协议或已有正式人工对账结束。

0126 先核对前置账本、活动审批/引用事务和重复目标，再归档七表原行以及删除菜单对应的角色、套餐原行，随后删除旧表及入口权限。相同租户、数据源、表名存在多个活动目标时整个事务拒绝；管理员须逐条调查实际部署身份，不能自动合并 ID。`dh_modeling_workspace_removal_archive` 仅是不可变迁移证据，无产品/API 入口。完整备份仍是恢复旧版的前提。

访问范围改为 RBAC 与租户隔离，原对象 ACL 不再生效，原始 ACL 仍保存在统一映射及退役归档中。保留真实外部引用、标准门禁、结构检测、部署日志与元数据/血缘登记。建模专属回执删除后，引用盘点与新的建模写入之间没有分布式事务保证；数据源删除和危险结构操作须停写、复盘引用后执行，不得把单次空盘点当作并发安全证明。

0126 完成后禁止重跑 0123/0124 的旧审查流程；按环境迁移账本跳过历史脚本。0126 自身允许重放并拒绝旧退役表重新出现，不改写首轮归档。已发布设计和部署快照的不可变触发器继续保留；删除的仅为旧审批冻结与报告触发器。

## 0126 已执行验证

2026-10-10 使用本机隔离 PostgreSQL 18（127.0.0.1:15437）克隆专用 through0125 空测试库，`verify_workspace_removal.py` 的 8 组全部通过：七表原行逐一比对、模型/版本/统一映射保持、角色/套餐与按钮清理、重放保留首次证据、归档新增/更新/删除/清空拒绝、同目标并发唯一约束，以及活动审批、ACTIVE 建模引用和重复目标的整笔回滚。完整 through0126 基线导入 `modeling_fresh_0126` 成功。上述库均为本次专用测试库，未执行生产升级或用户目标表 DDL。

完整升级链验证已扩展至 0126：`verify_migrations.py --port 15437 --source-db modeling_before --baseline-db modeling_fresh_0126` 在隔离 PostgreSQL 18 实跑 15 组通过。旧 ACL 审查与历史断言均先于 0126；退役后仅重放 0126 并核对映射证据不变及新库最终结构。

最终版本的全量基线另在空库 `modeling_fresh_0126_final` 导入成功；该库与完整升级链库 `modeling_unified_7e2e931364_positive` 的最终断言均通过。两库 `pg_dump --schema-only --no-owner --no-privileges --schema=datahub-center` 仅移除随机 restrict 标记后，结构文本逐字一致，SHA-256 为 `3220baf5dbe615f1eb412030366199b488fe75520ec36dd2de07000bce560f11`。

验证范围说明：上述导入与哈希对应本任务交付至 0126 时的基线快照。验收期间工作区另有并行任务写入 0127 及更新基线标记；本任务保留这些改动，不将 0126 的验证结果表述为 0127 已通过，也不把当前最新基线自动视为本次已验证制品。部署须以最终统一确认的迁移账本及制品哈希重新核对。

2026-10-10 20:45:57，最终后端全量建模测试完成，耗时 7 分 49 秒，退出码 0：313 例，失败 0、错误 0、跳过 0，Surefire XML 汇总与控制台一致。clean 范围仅为 `yudao-module-modeling-api` 和 `yudao-module-modeling-server`，随后重编译并执行两模块测试；依赖前置为下述 69 模块当前主代码安装，并已传入专用本机 PostgreSQL DDL 配置。日志保存在 `$env:TEMP/codex-unified-modeling-final-tests.log`，不依赖旧建模 target 中的类或报告判断通过。

本轮包含真实 PostgreSQL 的 Concept 5 例、LegacyMigration 8 例、Planning 5 例、SingleTarget 2 例、DeployPersistence 9 例、Repair 6 例、DdlPlan 5 例；另有 DeployService 47 例、统一发布权限 1 例和统一关系 2 例等测试。早期失败涉及统一列测试夹具、退役后的规划断言和缺失历史预检，已随最终代码与测试适配修复；只读历史预检从 Git `5d619f33` 原样恢复到测试资源。最新完整基线由 `ModelSingleTargetMigrationPostgreSqlTest` 导入成功，但这一用例不能替代 0127 专项的数据保全、首次升级与重放验证，本任务不据此声称 0127 迁移已完成验收。

2026-10-10 20:33，Dataforge 引用控制专项已实跑 PostgreSQL 16 Testcontainers：`DatabaseReferenceControlPostgreSqlTest` 共 6 例，失败 0、错误 0、跳过 0。覆盖建模 mutation 拒绝且无落库、其他 owner 的幂等/租户隔离、冻结与注册并发互斥、危险动作凭证及人工对账。执行方式为先 `mvn -pl yudao-module-dataforge/yudao-module-dataforge-server -am install -Dmaven.test.skip=true` 安装当前 69 个主代码模块（通过），再单模块 `mvn -pl yudao-module-dataforge/yudao-module-dataforge-server test -Dtest=DatabaseReferenceControlPostgreSqlTest -Dsurefire.failIfNoSpecifiedTests=false`（通过）。最初直接 `-am test` 被无关 DolphinScheduler 组件 `TaskLogPageTest` 缺少 Mockito 测试依赖阻断，该依赖问题未在本任务改动；主代码安装阶段的 test.skip 不计作测试通过。


## 当前应用实现与核验边界

模型头提供可选 `grain`（“一行代表什么”）入口；明细和应用模型已填专属粒度说明时不要求重复输入。`grain` 已进入冻结快照、标准检查请求摘要和目标摘要；修改后必须按新内容重新检查。兼容 `POST /generate-physical` 要求同时具备 `modeling:model:save` 和 `modeling:model:publish`，不能用只保存权限生成发布产物。

新部署的建表、增加字段及 `VERIFY_ONLY` 全部走已验证的 DB Switch 产品能力检查，当前仅开放 PostgreSQL。非验证引擎不能仅凭列一致确认新发布成功；历史 `reconcile` 与登记重试保留原冻结目标恢复能力，不重跑 DDL。按目标 `deployedVersionId` 读取上次成功产物，使用冻结 UID/列名识别并拒绝改名与已管理字段删除；有成功证据但产物缺失、归属/目标不符或快照不可解码时拒绝新部署，不猜造来源。旧字段缺 UID 只允许保留原名。未曾由该产物管理的实库额外列予以保留，不据此生成删除动作；共同字段和基线漂移仍检查。

当前验收记录如下。后端结果已核对最终 Surefire 报告；前端边界、lint、typecheck、全量 Vitest 和生产构建各项最终均通过，原组合命令的超时与复测情况单独记录。

| 已执行检查 | 当前结果 |
| --- | --- |
| 治理粒度摘要与 canonical 专项 | 5 例，失败 0、错误 0、跳过 0 |
| `python tools/quality/run_all.py` | 最终复核通过；扫描 96 个 SQL、1076 个错误码、258 个 POM、6225 个 Java 文件；既有历史债务按 HEAD 规则接受 |
| clean modeling API/server 后的全量建模测试 | 313 例，失败 0、错误 0、跳过 0；真实 PostgreSQL DDL 已执行，2026-10-10 20:45:57 完成 |
| Dataforge 引用控制 PostgreSQL 16 Testcontainers | 6 例，失败 0、错误 0、跳过 0；执行与依赖测试限制见上文 |
| 前端边界、lint、typecheck | 各项通过 |
| 前端聚焦测试 | 13 例通过 |
| 前端全量 Vitest | `maxWorkers=2` 复测：147 个文件、1591 例全部通过 |
| 前端生产构建 | 退出码 0；11/11 个任务成功 |

原 `quality:antdv-next` 默认并发执行中，无关 `data-clean` 用例曾触发 5 秒超时；该用例单独运行 7/7 通过，随后以 `maxWorkers=2` 完成全量 Vitest 复测，未修改测试超时。上表记录各项门禁的最终结果，原组合命令仍保留当次超时结果。

生产构建存在非阻断告警，包括 CSS `:deep` 用法、产物大小、依赖相关告警及无关 supply 模块的 BigInt 构建目标提示；构建任务最终均成功。本轮未进行浏览器连接真实后端的端到端测试，页面与真实服务的交互仍需部署环境联调。

本文的 0123—0126 SQL 顺序对应统一设计与工作台退役；当前最终应用若同时包含业务过程/对象删除，须继续完成 [0127 功能移除说明](remove-business-process-object.md) 规定的前置与验证，再恢复业务写入。本文不改写 0127，也不沿用 0126 基线哈希作为 0127 的验证证据。

## 未正式使用的开发库：首次统一预检失败后清空

用户已明确开发库尚未正式使用，允许直接删除旧逻辑、物理模型及关联测试记录。补救文件为 `sql/postgresql/migrations/0128_modeling_clear-unused-before-unification.sql`。这是历史脚本交付后的新编号，不修改 0123 的预检；它只适用于 0123 未成功、尚无统一映射证据的数据库。

先停止旧应用和审批、检测、部署、血缘投递任务，在原失败连接执行 `ROLLBACK`。然后在执行补救文件的同一数据库会话开启清空：

```sql
ROLLBACK;
SET datahub.modeling_unused_reset='CLEAR_UNUSED_MODELS_BEFORE_0123';
```

完整执行 0128 文件；它自带事务。仅清空文件列出的 19 张表及数据源引用事务中 `owner=modeling` 的测试记录，范围是当前数据库所有租户。保留概念模型及其历史、数据源、分层、业务分类、业务过程、数据域、主题规划、全局检测配置，序列不重置。不使用级联删除或禁用触发器；未知外键依赖整笔拒绝。提交后原模型与部署、审批、检测记录无法恢复，只能另行恢复备份；外部数仓已经创建的表不删除。

共享治理标准映射、血缘事件、实际元数据与已应用历史均不修改。若旧模型仍有可重试的共享血缘事件，脚本整笔拒绝，不通过删模型绕过投递。自动审批审查曾拒绝扩展到治理映射更新与血缘事件删除，理由为超出已确认对象范围；本文件采用保留共享记录的边界。获取模型写锁后再次检查统一阶段，避免等待锁期间另一会话已完成准备；整个执行窗口不得并发运行迁移。

本次补救的 SQL 执行顺序：**仅此开发库先手动 0128，再回到 0123 → 0124 → 0125 → 0126 → 0127**；0124、0125 的会话切换声明沿用上文。正常升级至末尾的 0128 不开启清空开关即不修改任何数据；已统一的数据库即使显式开启也会拒绝。新库只导入当前完整基线，不运行补救清空。

可复现验证脚本为 `yudao-module-modeling/yudao-module-modeling-server/src/test/resources/modeling-unified/verify_unused_reset.py`，仅克隆显式提供的隔离 PostgreSQL 模板；不修改源库，也不连接用户开发库。

2026-10-10 实际在本机 PostgreSQL 18 的旧演示模板克隆中完成 9 组验证，退出码 0：未开启时无修改、19 表清空及规划/概念/共享记录/序列保留、统一前重放、已准备拒绝、清空后 0123—0127 全链、未知外键整笔回滚、共享未决事件拒绝、其他租户/任务/成功事件保留、已有映射证据拒绝。另在全新空库完整导入当前基线并执行未开启的 0128，退出码 0。`python tools/quality/run_all.py` 通过，扫描 97 个编号 SQL；`git diff --check` 通过。本轮只改 SQL、文档及 SQL 验证脚本，未重跑 Java/前端门禁；没有对用户开发库执行清空。

## 单文件接续升级（0129）

适用状态为 0123 已完整成功，0124、0125、0126、0127 可以尚未执行，或其中部分已经提交。0128 的清空不再重复执行；遗留在连接中的清空开关也不影响本文件。停止旧应用和相关任务后，在独立 SQL 连接中选择“执行整个脚本”，一次执行 `sql/postgresql/migrations/0129_modeling_consolidated-upgrade.sql`，无需额外设置参数或打开其他文件。

脚本开头的 `ROLLBACK` 用来退出连接中先前报错的事务，因此不要在包含其他未提交业务修改的连接运行。其后仅有一个新的 `BEGIN` 与 `COMMIT`，本次全部迁移修改一起成功或一起回滚；此前已经提交的阶段不受这次回滚影响。没有打开事务时，开头提示“没有正在进行的事务”属于正常提示。

该补救修正当前旧 0124 的 `dh_modeling_unificAation_map` 拼写，并在 `UNIFIED_MODEL_V1=CLEANED` 时跳过旧切换正文，避免 0126 后访问已删除的协作表。菜单切换保留首次前后镜像并验证重放；工作台退役保留完整归档；最后由业务过程对象退役阶段安装最终归属守卫，避免旧 0124 覆盖成仍依赖已删除业务过程表的版本。补救另安装不依赖退役表的版本、绑定、关系与分层守卫，以覆盖先前函数安装不完整的情况。

本文件不替用户确认未审查映射、不覆盖管理员改动过的菜单，也不删除共享治理记录。若这些实际业务前置不满足，会返回具体错误并回滚，而不是写入虚假的完成阶段。已完成数据库可重放，模型、已发布版本、原映射及归档证据保留。

SQL 执行顺序：对于上述已有库，**只运行 0129_modeling_consolidated-upgrade.sql**；成功后不再单独运行 0124—0128。文件末尾返回三个阶段 `UNIFIED_MODEL_V1`、`WORKSPACE_REMOVAL_V1`、`BUSINESS_PLANNING_REMOVAL_V1`，无错误且三者均为 `CLEANED` 才算完成。尚未完整成功执行 0123 的库不适用；新库仍只导入全量基线。

可复现验证脚本：`yudao-module-modeling/yudao-module-modeling-server/src/test/resources/modeling-unified/verify_consolidated_upgrade.py`，只在隔离 PostgreSQL 的唯一命名克隆库中执行。

2026-10-10 在本机 PostgreSQL 18（127.0.0.1:15437）完成 16 个检查组，退出码 0：从完成 0123、0124、0125、0126、0127 的五种状态接续升级及整文件重放；缺少 0123、未确认映射和后段菜单失败时整笔拒绝/回滚；最终归属守卫允许新模型并拒绝无效数据域；连接残留旧清空开关时重放仍保留新增模型；非空模型升级及重放保留模型身份、不可变版本、已确认映射、菜单备份、部署记录与基线摘要。仅写入 `modeling_repair_9b46be22d1_*` 独立克隆库，源模板及用户数据库均未修改。

另在新建空库 `modeling_consolidated_fresh_33fe2f7ae2` 完整导入 through0129 全量基线，再执行 0129 整文件重放，均退出码 0，三个阶段均为 `CLEANED`。`python tools/quality/run_all.py` 通过，扫描 98 个编号 SQL；本次仅修改 SQL、执行文档及 SQL 验证脚本，未重跑 Java/前端测试。0129 验证制品 SHA-256：`CE9ED97FD4F6802EBB25A2AF0DC67A1DC4F50F007649D860F15CCC54022F3FC1`。

## 缺少准备记录时的恢复（0130）

已确认的原始错误为 `0123 尚未完整成功；本脚本只接续已准备的统一迁移`，位置在 0129 的 `$stage_state$` 第一条检查。旧脚本把 0123 已提交作为前置；仅存在统一表结构或曾执行部分 SQL，不代表最后的准备账本已经写入。0123 本身只在全部准备正文完成后写入 `UNIFIED_MODEL_V1=PREPARED`，因此不能只补一行账本绕过。

0130 在一个事务中先判断真实阶段：无完成记录且旧表、旧列完整时，实际执行完整 0123 准备正文；已是 `PREPARED` 或 `CLEANED` 时跳过准备。随后执行 0129 已有的绑定、菜单和退役步骤。全部准备、回填、守卫与切换共同提交，后段失败也会回滚本次新做的准备。已经提交过的旧步骤不属于本次回滚范围。

如果缺少统一完成记录却已存在后续退役账本、归档、非空菜单切换证据，或者旧列/旧表已经被选段删除，脚本明确拒绝，不猜测原来的阶段。未确认映射及真实预检错误仍保留；0130 不执行 0128、不再次清空模型、不自动确认旧映射，也不包含本次表精简讨论中的新增删除。

SQL 执行顺序：已有开发库先停止旧应用和相关任务，再新开独立连接，**只执行完整 `sql/postgresql/migrations/0130_modeling_resume-missing-preparation.sql`**。无需分别执行 0123—0129，也无需额外设置参数；首行无活动事务的提示正常。文件最终无错误且三个阶段均为 `CLEANED` 才表示完成。新库仍只导入当前全量基线。

2026-10-10 真实 PostgreSQL 18 专项 `verify_missing_preparation.py` 执行 8 组全部通过，退出码 0：未执行 0123 时实际准备及重放；选段提交准备正文但缺账本时复现 0129 原错误并由 0130 恢复；后段菜单失败时准备与切换整体回滚且不补写账本；已准备、已完成 0127 和非空 0125 的模型及证据保真；后续归档或旧列缺失时明确拒绝。仅写入 `modeling_resume_3f8aa38443_*` 的 9 个独立克隆库，未修改源模板及用户数据库。

新建空库 `modeling_prepare_fresh_f2dd13450f` 导入 through0130 基线并整文件重放 0130 均通过。`python tools/quality/run_all.py` 通过（99 个编号 SQL），定向 `git diff --check` 通过；本次未修改 Java/Vue，未重跑前后端测试。0130 验证制品 SHA-256 为 `1B9E74AF117151258157252C0F5491363CA1E5888D746D47F2A671F64A645D7E`。
