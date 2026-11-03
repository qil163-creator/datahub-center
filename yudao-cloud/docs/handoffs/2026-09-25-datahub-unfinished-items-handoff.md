# 数据中台未完成事项详细交接清单

**当前检查点：2026-10-01 #12 事务完成异常识别与停批子项完成。** 不再仅依赖TransactionSystemException：逐项保存真实事务回调原异常，回调无失败却收到普通异常、回滚覆盖原异常及TSE均停批；正常应用失败经回滚返回后仍隔离继续、批末报失败。保留被普通回滚异常覆盖的取消/中断/原Error，真实Spring beforeCommit/afterCommit回调故障亦停批；先前已提交子单保留，新节点重扫不重复。17项先红全部失败后，定向36项和最终9类535项（真实PG335，新增21）均零失败/错误/跳过，5项通知诊断仍过滤、未修。最终门禁及补丁检查通过，临时库精确容器已清理、端口释放，业务库未操作。详见[专项记录](../reviews/2026-10-01-quality-expiry-completion-phase.md)。无新SQL/API/前端/Job/通知协议变化，最高0076；内存回调异常记录不是持久失败/UNKNOWN账本，退避/耗尽/人工处置、再次到期政策及历史核验仍待，#12/#15/#17/#11与27产品矩阵不结项。以下为历史检查点。

**当前检查点：2026-10-01 #12 新例外到期动作持久结果绑定子项完成。** 同一逐单事务中保存真实后续工单编号、问题编号多重集SHA256/数量及创建时接收人，CAS后复读；异常、零行、静默改值/租户污染整笔回滚，旧历史不回填、不按同根候选追认。0076新增五个可空列、完整性约束及包含逻删历史的租户目标唯一索引，基线相关片段同步；新只读报告v2区分直接引用与legacy，当前目标关系和原通知义务独立观察，候选仍UNPROVEN、不代表到数/恢复/送达。最终9类514项（真实PG314，新增47）零失败/错误/跳过，完整psql SELECT-only角色、只读门禁、后端门禁及补丁检查通过；5项已知通知诊断仍排除、未修。临时库精确容器已删除、端口释放，未动业务库。首轮测试失败及夹具同步记录保留，详见[专项记录](../reviews/2026-10-01-quality-expiry-result-binding.md)和[操作规程](../operations/quality-exception-expiry-inventory.md)。未执行业务库迁移/清点，上线先0076、排空并统一治理节点再恢复任务；#12整体、#15/#17/#11及27产品矩阵不结项，以下为历史检查点。

**当前检查点：2026-10-01 #12 历史例外到期动作只读清点工具子项完成。** 新增运维SQL，按授权单租户、动作游标固定上界、每页≤200动作/每动作≤20候选，在真实REPEATABLE READ READ ONLY中报告原单/提交缺失、逻辑删除、同提交重复、当前关系和通知意图观察。动作无followUpId，任何同根候选始终UNPROVEN；当前关系/owner不是原到期冻结证据，SENT不等于送达，不自动修复/重投。最终9类467项（真实PG267）零失败/错误/跳过，含31项清点用例；完整psql SELECT-only角色及标识符/非法参数/服务器只读门禁另验通过。初轮27项21错误如实保留，修正参数规划折叠和夹具NOT NULL字段后重跑；5项通知诊断仍过滤、未修。门禁通过，无新编号迁移/结构/生产Java/前端变化，最高0075；临时库已清理，未清点业务库。详见[操作规程](../operations/quality-exception-expiry-inventory.md)与[专项记录](../reviews/2026-10-01-quality-expiry-inventory.md)。现场全量清点、可信绑定核验和授权数据处置仍待；#12/#15/#17/#11及27产品矩阵不结项。以下检查点为历史。

**当前检查点：2026-10-01 #12 旧编辑入口客户端读取版本保护子项已完成。** 更新必须携带打开时的 `expectedVersion`，授权后拒绝缺失/负数/溢出，父锁内同时复验客户端版本与本请求授权快照；陈旧编辑不能覆盖别人已保存的名称/问题关联，原 CAS、状态及整单事务保留。仅此字段阻止 JSON 小数截断，零版本有效，创建仍不需版本。前端独立 UpdateRequest/boolean 回执、非法版本不发请求、冲突不自动 GET/换版本重投，当前创建页面不增加编辑入口。最终9类限定回归436项（真实PG236）和前端20项均零失败/错误/跳过，两文件 ESLint/Oxlint、边界、后端门禁通过；应用类型仍同61条历史错误，无新增。5项已知通知诊断明确排除、未修，不能称全模块全绿。无新SQL，最高0075；临时PG已清理，未操作业务库。详见[专项记录](../reviews/2026-10-01-quality-update-client-version.md)。旧PUT客户端必须同步升级，否则不带版本的更新会被拒绝；#12整体、#15/#17/#11和27产品矩阵仍未结项，下方检查点为历史。

**当前检查点：2026-10-01 #12 例外到期持久公平扫描子项已完成。** 新增租户唯一游标/短事务预约/固定轮次上界，首20条持续故障不再挡住第21条；预约后消失及提交回执丢失的项可在回绕后重纳入，空轮次不会冻结旧上界。取消、资源/租户污染和事务异常不释放候选，原完整逐单事务/父锁/幂等继续保留。最终8类限定回归406项（真实PG223）零失败/错误/跳过；中途一次旧用例初始化出现回环建连 BindException，保留失败日志，测试改用每夹具小连接池后完整重跑通过。5项通知诊断未执行/未修，不计通过；补充Spring装配检查另记、不并入406。新增0075扫描游标迁移及基线片段，未操作业务库/历史通知，部署须先迁移再统一升级治理节点。质量门禁通过，详见[专项记录](../reviews/2026-10-01-quality-expiry-fair-scan.md)。持久单项失败/UNKNOWN账本、退避/耗尽/人工处置、再次到期政策、历史残缺对账和通知授权仍待；#12/#15/#17/#11整体及27产品矩阵不结项。以下检查点为历史。

**当前检查点：2026-10-01 #12 例外到期同批普通故障隔离与真实失败报告子项完成。** 逐单事务退出后收集普通执行异常、继续本批其他候选，批末仍向原租户 Job 报告失败；诊断完整保留、根摘要仅含安全批次信息。取消/中断优先停止，事务完成异常或资源/租户污染停批；候选查询之后、任何首项写入之前也复验上下文，空候选不例外，合法非事务同步保持兼容。最终8类限定回归375项（真实PG192）通过，含42项本轮增量；5项已知通知诊断仍未执行/未修、不计通过。质量门禁通过，详情见[专项记录](../reviews/2026-10-01-quality-expiry-batch-failure-isolation.md)。单项提交异常不等于确定回滚；本轮未建持久UNKNOWN/失败处置账本，前20条全故障时第21条仍可能饥饿。重复到期政策、历史残缺对账、通知授权及其余到数/恢复证明仍待，#12/#15/#17/#11整体及27产品矩阵不结项。无新SQL/前端/Job定义变化，最高迁移0074，临时库已清理；下方检查点为历史。

**当前检查点：2026-10-01 #12 例外到期原子创建与已完成项扫描推进子项已修复。** 原单同租户行锁内复验当前 ACTIVE/EXCEPTION 提交及到期资格，审计/新单/完整问题关联/PENDING 通知意图逐单同事务；抛错、零行或部分漏写回滚后可重试，锁内无 RPC。扫描先排除无效及已处理项再 LIMIT 20，已完成第一页不再阻挡后续。8 类限定回归 330 项（真实 PG 147）通过，另 6 次真实并发验证通过，其中 3 次移除额外夹具动作唯一索引；两组不简单累加。5 项已知通知诊断仍未修、未计通过，UNKNOWN/领取/索引迁移仍待明确授权。质量门禁通过，无新 SQL/前端/Job 定义变化，最高迁移 0074；[专项记录](../reviews/2026-10-01-quality-exception-expiry-atomicity.md)列出命令、边界和部署注意事项。旧单重开后的再次到期政策、持续故障隔离/饥饿和历史残缺审计对账仍待，#12/#15/#17/#11 及 27 产品矩阵不提前结项。较早检查点为历史。

**当前检查点：2026-10-01 #12 旧删除入口修复完成；通知迁移待明确授权。** 删除使用独立权限与既有可见对象范围、同租户工单行锁、授权/版本复验、实际历史提交保护和严格逻辑删除；关联抛错或静默跳过均整单回滚，不强加处理权限、不新增状态白名单。最终 8 类限定回归 298 项（真实 PG 115）通过；明确排除了 5 项已复现但仍失败的通知诊断，不能表述为完整 PG 类或模块全绿。质量门禁通过，详情见[专项记录与授权范围](../reviews/2026-10-01-quality-delete-concurrency-and-notify-blocker.md)。通知领取协议、旧记录批量 UNKNOWN 与唯一索引补丁被自动审批阻止，没有落盘/执行，也未修改 System；最高迁移仍 0074。无本轮新 SQL/前端/Job 变化，#12/#15/#17/#11 整体和 27 产品矩阵均未结项。较早检查点保留为历史。

**当前检查点：2026-10-01 #12 旧编辑执行期竞争与通知提交边界两项修复完成。** 编辑与提交/确认共用工单行锁，复验授权快照/版本/状态并 CAS 推进版本，问题关系同事务；通知只提交持久 PENDING 台账，由原逐租户 Job 发送，禁止事务内调用派发入口，必须收到有效正数消息编号才记 SENT。最终 8 类 282 项（真实 PostgreSQL 99）零失败/错误/跳过，质量门禁与补丁检查通过。详情、先红后绿证据、通知延迟变化及未验证项见[本轮专项记录](../reviews/2026-10-01-quality-edit-and-notify-commit-boundary.md)。无新 SQL/公共 API/前端/Job 定义变化，未操作业务库。多节点通知领取/远端幂等、UNKNOWN 对账及重试耗尽处置仍未修复；#12/#15/#17/#11 四项整体仍未完成，27 产品矩阵不变。以下较早检查点为历史记录，当前状态以本条和各节清单为准。

**2026-10-01 #12 继续推进：复测启动原子性与确认时实际执行证据复验两个限定子项已修复。** attempt INSERT 与 submission 绑定 UPDATE 必须各影响 1 行，失败整笔回滚且不 dispatch；确认先保留原 v3/managed-generation/问题身份门禁，再读取实例、冻结定义和基线/当前审计，使用原领域判定器重核，并要求完整检查事实与原决定一致，不以另一个 PASS 替换。14 个真实 PG 故障反例先红后绿；最终 8 类 272 项（真实 PG 89）零失败/错误/跳过，ORG 阈值内非零失败数确认正例保持通过。详情、命令、测试边界见[本轮专项记录](../reviews/2026-10-01-quality-confirm-execution-binding.md)。无新 SQL/公共 API/前端变化，27 产品支持范围不变；#12/#15/#17/#11 四项整体仍未完成。旧编辑入口竞争和事务内远端通知列为 #12 后续代码点，尚未修复，不把本轮平台证据复验等同到数/恢复证明。

**2026-10-01 接续 #12 → #15 → #17 → #11：本轮推进的是明确子项，不将四项整体提前结项。** 复测决定升级 v3，绑定轮次、提交内容和当前完整问题身份；缺失问题不得被转换函数过滤，三处状态写入非 1 行则整单回滚。续期按用户决定独立审批/独立来源，父申请暂停、撤销、到期不级联；继承用途/义务/冻结范围，补正不能扩大范围。治理权限新增精确 schema 与历史核验绑定（0074），SQL 技术能力复用 DB Switch；新增可信行条件合并及原始/执行双 SQL 返回前复验。Doris 修复大小写敏感表名误匹配，Greenplum 7.1.0 补原生 VARCHAR/TEXT 和混合整数文本键/文本机构的有限路径。验证明细和限制见[本轮专项记录](../reviews/2026-10-01-datahub-recheck-renewal-schema-and-greenplum.md)。部署必须先执行 0074，再统一升级相关应用；历史 NULL schema 不自动回填，也不是通配符。

**2026-09-29 #19 收尾：撤销重算、人工核查和全端状态一致性的可代码化剩余子项已完成（按用户确认的三项政策）。** NOT_FOUND 受控重投 `POST /asset/market/apply/redeliver-delivery`（独立权限 `asset:market:redeliver` 0073、必绑核查决定、仅限完整匹配的撤销/暂停 MANUAL/UNKNOWN 原操作按原 operationId 重发一次、GRANT 拒绝、意图审计唯一请求 0072）；历史处置结论扩展到其余待核查分类 `POST /supply/app/api-auth/review-disposition`（继续待核查/需撤销后重申请，只记录不动授权，新增 0071）；撤销/开通/收回/自动到期补普通审计事件（与状态变更同事务）。全端统一状态与"撤销受理/远端已确认撤销"口径核对完成（此前数据库时钟与动作呈现已由 2026-09-28 各轮完成）；实际访问范围专项已有真实 PG 覆盖并回归。前端交付受控重投按钮/弹窗与 Supply 处置结论/历史弹窗。真实 PG 专项：`MarketDeliveryRedeliverPostgresTest` **9/0/0/0**、`SupplyGrantDisposalPostgresTest` **23/0/0/0**（2026-09-29T20:0x，一次性容器 `q19-sharing-20260929` 仅绑 `127.0.0.1:15494`，测完删除）；聚焦回归与模块完整回归、质量门禁见[本轮记录](../reviews/2026-09-29-sharing-redelivery-and-disposition.md)。保持现状并记录的边界：System 角色提交后复核、校核评价唯一约束、资产下架禁止提交；真实 Supply/Asset 联调与浏览器验收保留为上线事项。#19 代码侧完成，整项不宣称生产级验收结束。

**2026-09-29 #18 收尾：历史数据处置的可代码化剩余子项已完成（按用户确认的四项政策）。** 处置必填 `basisReviewRequestId` 绑定核查决定（同一原操作、未删除、14 个快照字段逐项复验，不一致先重新记录决定）；新增批量处置 `POST /asset/market/apply/delivery-disposal-batch`（≤20 条、逐条独立成败、同批禁止重复原操作）；新增 Supply 孤立旧 ADMIN 处置结论 `POST /supply/app/api-auth/legacy-admin-disposal`（继续隔离/确认已撤销，只记录不动授权，新增 0070）；新增只读一致性快照清点 `GET /supply/app/api-auth/review-inventory`（三类账本单一 REPEATABLE_READ 事务全量分类计数，截断显式标记）。前端交付处置绑定弹窗、Supply 处置结论/历史/清点弹窗与 API 客户端（批量页面多选入口未做）。部署切换与回退规程落 [operations/sharing-disposal-deployment-switch.md](../operations/sharing-disposal-deployment-switch.md)。真实 PG 专项：`MarketDeliveryDisposalPostgresTest` **38/0/0/0**、`SupplyGrantDisposalPostgresTest` **14/0/0/0**（2026-09-29T17:00，一次性容器 `q18-sharing-20260929` 仅绑 `127.0.0.1:15494`，测完删除）；模块完整回归 Asset **79 类 3818 项 0 失败/错误**、Supply **33 类 497 项 0 失败/错误**；质量门禁 39 SQL / 1117 错误码 / 200 POM / 5116 Java 通过；见[本轮记录](../reviews/2026-09-29-sharing-disposal-completion.md)。A/B 来源真实多服务处置与部署切换执行保留为上线验收；旧历史批准原件核验依赖业务库数据，由授权管理员按规程执行。#18 代码侧完成，整项不宣称生产级验收结束。

**2026-09-29 #11 接续：DB2 12.1.5.0 普通权限仍起不来，未测清空代际。** 官方镜像 `icr.io/db2_community/db2:latest`，镜像 Id `sha256:2de8151713c261843868c5c3411b57be6ae79d99d70a5b3022337836776bfda6`，与 2026-09-25 和 #17 的 digest 相同。`db2level` 为 `DB2 v12.1.5.0`，`special_85816`，`DYN2606252315AMD64_85816`，Fix Pack `0`。一次性容器 `q11-db2-20260929` 主机名 `q11db2`，只绑定 `127.0.0.1:15505→50000`，4 GiB / 2 CPU / shm 1 GiB，无特权、无额外 capability、无宿主目录，IPC 与网络都不是 host，测完已删除，镜像保留。入口 `mount -o remount,suid /database` 得到 permission denied；`.fenced` 不存在，脚本仍继续。`db2sysc` 不是 setuid，也没有 security 扩展属性。自动内存看到宿主可见 `13280526876` 字节，许可降到 `8589934592`，cgroup 上限是 4 GiB，`oom_kill` 一直是 0。四次 `db2start` 都是 SQL1042C：`msgrcv` 得到 `ENOMSG (42)`，ZRC `0x8300002A`，子进程提前退出且没有自己的诊断文件。把 `INSTANCE_MEMORY` 改成 `262144` 后的第四次仍相同。容器内不能改 `kernel.msgmax`。`db2 list db directory` 是 SQL1031N，Q11DB 没有建成。因此没有测 TRUNCATE，也没有改 DB2 身份、键、机构或治理代码。不能把容器 running 当数据库可用，也不能套其他产品。#17 曾用特权启动同一摘要，那不是本次授权。见[本轮记录](../reviews/2026-09-29-quality-db2-environment.md)。#11 不能关闭。

**2026-09-29 #11 接续：MongoDB 7.0.43 没有可证明的清空代际，未打开自动验收。** 官方镜像 `mongo:7.0`，镜像 Id `sha256:9854f7139445d766a9523571d6f047530c45547460ffcf8259eb2bf4264632ca`，与 2026-09-24 的 digest 相同。`buildInfo.version` 为 `7.0.43`，`modules` 为空。一次性容器 `q11-mongo-20260929` 主机名 `q11mongo`，只绑定 `127.0.0.1:15504→27017`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，WiredTiger 缓存 0.5 GiB，未开认证，也不是副本集，测完已删除，镜像保留。库 `q11db` 在删除容器前已 `dropDatabase`。没有 `truncate` 命令（code `59` `CommandNotFound`）。`deleteMany` 不改变集合 uuid 或 WiredTiger uri；插入和删除会改变 `count` 与逻辑 `size`。`drop/create` 才更换 uuid。`collMod` 改变 `options` 但不改变 uuid。4000 行在未落盘时 `storageSize` 仍是 `4096`，清空也不变；`compact` 会改变仍有数据的集合的 `storageSize`。因此 2026-09-24 已有的集合指纹不是清空代际，本轮没有改它，也不做整数键或治理链。不能套 Elasticsearch 或 InfluxDB。见[本轮记录](../reviews/2026-09-29-quality-mongodb-key-coverage.md)。#11 不能关闭。

**2026-09-29 #11 接续：InfluxDB 3 Core 3.11.5 没有可证明的清空代际，未发关系凭证。** 官方镜像 `influxdb:3-core`，镜像 Id `sha256:43991052bc05fd1ee494998de89a55699587c8c0a6591dd33f30c7246752c7f8`，与 2026-09-24 的 digest 相同。`influxdb3 --version` 为 `InfluxDB 3 Core, 3.11.5, revision f083f73c92eaa87186d39c81bbd8afc3a3fd8d94`。一次性容器 `q11-influx-20260929` 主机名 `q11influx`，只绑定 `127.0.0.1:15503→8181`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，文件对象存储，`--node-id q11node`，关闭 package-management，health 与 ping 不做授权检查，测完已删除，镜像保留。库 `q11db` 在删除容器前已 `delete database`，之后 `show databases` 只剩 `_internal`。没有 `TRUNCATE`：SQL 返回 HTTP 405，`Unsupported SQL statement: TRUNCATE TABLE`。`UPDATE` 与 `DELETE` 是 HTTP 400 `DML not supported`，`DROP TABLE` 是 HTTP 400 `DDL not supported`。实际清空是 `delete table`。不加硬删时，活表 `q11heap` 被改名为 `q11heap-20260929T115941`，旧的 4 行仍可查；再次写入又建出同名 `q11heap`。`--hard-delete now` 同样只把当时的活表改名为 `q11heap-20260929T115942`，那 1 行仍可查，并没有立刻抹掉。`information_schema.tables` 只有 catalog、schema、name 和 `BASE TABLE`，没有表 id。`system.parquet_files` 在这些刚写入的表上是 0 行，数据还在 WAL。WAL 文件序号随写入增加到 `00000000007.wal`，软删和硬删都不增加。catalog 日志序号在删除和随后的写入时都会增加，建旁表 `q11other` 也会增加，最高到 `...11.catalog`，而且 SQL 看不到。启动时的 server uuid 和 catalog uuid 不随删表变化。因此没有新增身份实现，也不打开自动整改验收。既有 signed Int64 单键、2–4 列复合键、整数机构和 NOT_NULL 组件扫描仍不能自动验收。不能把 node-id、库名、表名、墓碑时间戳、WAL 序号或 catalog 日志序号当成代际，也不能套 SQLite 或 Hive。见[本轮记录](../reviews/2026-09-29-quality-influxdb-key-coverage.md)。#11 不能关闭。

**2026-09-29 #11 接续：SQLite 3.31.1 没有可证明的清空代际，未发关系凭证。** 引擎是仓库 BOM 里的 `org.xerial:sqlite-jdbc:3.31.1`，`sqlite_version()` 为 `3.31.1`，文件头版本号 `3031001`。不是容器，也不是更新的 sqlite3 命令行。临时文件测完已删除，没有打开业务库。`TRUNCATE TABLE` 是语法错误（`SQLITE_ERROR` / code 1，`near "TRUNCATE"`）。普通表 `rootpage` 在插入、更新、清空和再次插入后仍是 `2`，`DROP/CREATE` 也复用 `2`。800 行清空会增加整库 `freelist_count`，再次插入同样行数又把它用回 `0`；旁表清空也会改变这份计数，被测表的 `rootpage` 和行数不变。`schema_version` 不随清空变化，DDL 和 `VACUUM` 会增加。`data_version` 对同一连接的写入不变，另一连接的插入或删除都会增加。`sqlite_sequence` 随插入增加，清空表不变。WAL 未 checkpoint 时主文件变更计数不随清空变化。`auto_vacuum=FULL` 的 `page_count` 插入和清空都会变，`rootpage` 仍不变。因此没有新增身份实现，也不做整数键或治理链。不能套 Hive 或其他产品。见[本轮记录](../reviews/2026-09-29-quality-sqlite-key-coverage.md)。#11 不能关闭。

**2026-09-29 #11 接续：Hive 4.1.0 没有可证明的截断代际，未发关系凭证。** 官方镜像 `apache/hive:4.1.0`，镜像 Id `sha256:b957bb0e698e560d24e162b7549d79f191d58703e92899a886618c317f6e8f30`，与 2026-09-25 的 digest 相同。`SELECT version()` 为 `4.1.0 r75e40b7537c91a70ccaa31c397d21823c7528eeb`。一次性容器 `q11-hive-20260929` 主机名 `q11hive`，只绑定 `127.0.0.1:15502→10000`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，内嵌 Derby，事务管理器是 `DummyTxnManager`，测完已删除，镜像保留。库 `q11db` 在删除容器前已 `DROP DATABASE ... CASCADE`。质量路径使用的外部 TEXTFILE 且 `external.table.purge=true` 上，目录 inode 和 location 在插入、覆盖、非空截断和空表再截断后都不变；目录 mtime 在插入和截断时都会变。数据文件截断后消失，下一次插入才出现新 inode；删表重建时目录 inode 复用了刚释放的校验文件 inode。`transient_lastDdlTime` 随插入增加，截断不变。行数和文件统计随插入变化。`COLUMN_STATS_ACCURATE` 只在第一次非空截断时丢掉列统计，之后不再随截断变化。`DESCRIBE EXTENDED` 的 metastore `id` 在写入和截断时保持 `10`，`DROP/CREATE` 才变为 `11`；`writeId` 一直是 `0`。普通 `CREATE TABLE` 在这台镜像上被记成外部表。ACID ORC 建表失败，错误 `10265`；`UPDATE` 失败，错误 `10294`。因此没有新增身份实现，也不打开自动整改验收。既有 signed 整数键、机构和 NOT_NULL 读取仍是 `RELATION_UNPROVEN`。不能把 location、createTime、DDL 时间或单个 metastore 表 id 当成完整关系身份，也不能套其他产品。见[本轮记录](../reviews/2026-09-29-quality-hive-key-coverage.md)。#11 不能关闭。

**2026-09-29 #11 接续：SQL Server 2022 16.0.4295.3 RTM-CU27 没有可证明的截断代际，未发关系凭证。** 官方镜像 `mcr.microsoft.com/mssql/server:2022-latest`，镜像 Id `sha256:4402d880dd4c34bfa7d8705e56a86cd6c88da80a1f6bbbe741f999e76264a090`，与 2026-09-25 的 digest 相同。`@@version` 为 `Microsoft SQL Server 2022 (RTM-CU27) (KB5104824) - 16.0.4295.3`，`ProductLevel` 为 `RTM`，`ProductUpdateLevel` 为 `CU27`，版本 `Developer Edition (64-bit)`，排序规则 `SQL_Latin1_General_CP1_CI_AS`。一次性容器 `q11-mssql-20260929` 主机名 `q11mssql`，只绑定 `127.0.0.1:15501→1433`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测完已删除，镜像保留。用户库 `codex_q11` 在删除容器前已 `DROP DATABASE`。堆表和聚集索引上，`INSERT`/`UPDATE`/`DELETE` 与非空或已空的 `TRUNCATE` 都不改变 `object_id`、`partition_id`、`hobt_id` 或 `allocation_unit_id`。`first_page`、`first_iam_page` 和聚集索引的 `root_page` 截断后清零，再次插入复用同一组页指针。`ALTER TABLE REBUILD` 会更换 `hobt_id`，但那不是截断代际。`DROP/CREATE` 改变 `object_id`。`modify_date` 不随截断变化。因此没有新增身份实现，也不打开自动整改验收。既有 signed 整数键、机构和 NOT_NULL 读取仍是 `RELATION_UNPROVEN`。这次是单独测量，不能用 SQL Server 2000 的结论代替，也不能写回 2000。见[本轮记录](../reviews/2026-09-29-quality-sqlserver-key-coverage.md)。#11 不能关闭。

**2026-09-29 #11 接续：Oracle AI Database 26ai Free 23.26.3.0.0 普通堆表已有原生身份和整数复测，整项仍未完成。** 官方镜像 `container-registry.oracle.com/database/free:latest`，digest `sha256:f988b0c04c4c386cd306a2a914c0d7a9702d83acc31b064a28ad8eb6278a8fba`。`banner_full` 以 `Oracle AI Database 26ai Free Release 23.26.3.0.0` 开头并包含 `Version 23.26.3.0.0`。一次性容器 `q11-ora-20260929` 只绑定 `127.0.0.1:15500→1521`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测完已删除，镜像保留。协议 `ORACLE_23_26_3_0_0_HEAP_V1` 只接受普通永久堆表。非空表 `TRUNCATE` 改变 `DATA_OBJECT_ID` 且不立即复用；已经空着的表再 `TRUNCATE` 不改变 `DATA_OBJECT_ID` 和 `LAST_DDL_TIME`，所以不把时间列当代际。`INSERT`/`UPDATE`/`DELETE`、注释和索引不变。`ALTER TABLE MOVE` 也会改变 `DATA_OBJECT_ID`。`DROP PURGE` 后同名重建同时改变 `OBJECT_ID` 和 `DATA_OBJECT_ID`。旁表不变。指纹绑定 `CURRENT_USER`。只读用户能独立证明，但不能复用属主指纹；撤销表的 `SELECT` 后得到 `0A000`。`SYS.SYS_CONTEXT` 在本构建上是 ORA-00904，调用 `SYS.STANDARD.SYS_CONTEXT`。字典用 `SYS.ALL_*` 和 `SYS.V_$VERSION`。`SYSTEM` 不能撤销 `SYS.ALL_POLICIES` 上 PUBLIC 的授权（ORA-01927）。拒绝视图、全局临时表、索引组织表、分区父表、外部表、簇表、物化视图、VPD 和加引号的 `KeepCase`。整数单键沿用 `SHA256_SORTED_INT64_V1`，复合键 `SHA256_SORTED_ORACLE_NUMBER_INT64_TUPLE_V1`，机构 `ORACLE_NUMBER_INT64_DECIMAL_V1`。只证明有符号 `NUMBER(p,0)` 的 Int64 范围与 NOT_NULL；2–4 列顺序会改变复合哈希。空串存成 NULL，不套神通的空串转换。扫描编码此前已有，本轮才把 ORACLE 放进产品验收门禁。`parseBound` 仍拒绝，这不是 #17 精确表定位。7 类 152 项，0 失败/错误/跳过，其中实库 31 项。见[本轮记录](../reviews/2026-09-29-quality-oracle-key-coverage.md)。物理恢复、PDB 切换、RAC、其他版本和其他规则仍待。不能套达梦、金仓、神通或 PostgreSQL。#11 不能关闭。

**2026-09-29 #11 接续：Elasticsearch 8.15.3 没有可证明的截断代际，未发凭证。** 官方镜像 `docker.elastic.co/elasticsearch/elasticsearch:8.15.3`，digest `sha256:01c1732062b4a846c5ca687b0094b89bad0bfed00c6d71626db32fb8f3131a78`，build `f97532e680b555c3a05e73a74c28afb666923018`，Lucene 9.11.1。一次性容器 `q11-es-20260929` 只绑定 `127.0.0.1:15499→9200`，4 GiB / 2 CPU / shm 1 GiB，堆 1 GiB，无特权、无宿主目录，单节点且关闭认证、注册和 GeoIP 下载，测完已删除，镜像保留。普通索引的 `index.uuid`、`history_uuid` 和 `translog_uuid` 在写入、更新和 `_delete_by_query` 后不变，同名删除重建才变。序列号、提交代际和段代际随写入变化。没有 `TRUNCATE`。数据流 generation 和后备索引名在删流重建后复用，清空文档不改变后备索引 uuid。因此没有实现身份、整数键或治理链。不能套关系库的截断代际，也不改 #17 的拒绝。见[本轮记录](../reviews/2026-09-29-quality-elasticsearch-key-coverage.md)。#11 不能关闭。

**2026-09-29 #11 接续：优炫 UXsinoDB 2.1.0.3 永久 heap 已有原生身份和整数复测，整项仍未完成。** 第三方镜像 `caimsdocker/uxdb:1.0`，digest `sha256:5ee70dd8dbfd057b38f66ce7a9dab3cb11de181affc3a789fa040f24b79cfb72`。`ux_catalog.version()` 前缀是 `UXsinoDB 2.1.0.3`，`server_version=10.0`、控制版本 `1002` 和目录版本 `201707211` 只是本构建见证。目录是 `ux_catalog`，不是 `pg_catalog`；`ux_am` 没有 heap，永久堆的 `relam` 是 `0`。一次性容器 `q11-uxdb-20260929` 只绑定 `127.0.0.1:15498→5432`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测完已删除，镜像保留。协议 `UXDB_2_1_0_3_HEAP_V1` 只接受永久 heap。写入和更新不改变指纹，TRUNCATE 改变 `relfilenode`，同名重建同时改变 OID 和 `relfilenode`。指纹绑定 `current_user`。只读角色能独立证明，但不能复用属主指纹；撤销 `ux_catalog.ux_policy` 的 PUBLIC SELECT 后得到 `42501`。拒绝视图、物化视图、UNLOGGED、分区、继承、fillfactor、规则、临时表和 RLS。全局临时表和外部表本轮没有建成。整数单键沿用 `SHA256_SORTED_INT64_V1`，复合键 `SHA256_SORTED_UXDB_INT_TUPLE_V1`，机构 `UXDB_SIGNED_INT64_DECIMAL_V1`。只证明 signed int2/int4/int8 与 NOT_NULL。空串不是 NULL，数字 `0` 不等于空串，不套神通的空串转换。普通标识符按给定拼写加双引号，不在引用时折叠。`parseBound` 仍拒绝，这不是 #17 精确表定位。5 类 139 项，0 失败/错误/跳过，其中实库 21 项。见[本轮记录](../reviews/2026-09-29-quality-uxdb-key-coverage.md)。物理恢复代际、文本键、其他规则和原生 COPY 故障仍待。不能套 PostgreSQL、瀚高、Greenplum 或金仓。#11 不能关闭。

**2026-09-29 #11 接续：SQL Server 2000 8.00.760 SP3 没有可证明的截断代际，未发凭证。** 第三方镜像 `shirleyqe/mssql2000`，digest `sha256:4125eb71fcd57ddf8de8e713b31d0c4d399f8fd785ce7af148ec32563cf90b88`。`@@version` 与 #17 相同，`ProductLevel` 为 `SP3`，版本 `Desktop Engine`。一次性容器 `q11-sql2000-20260929` 主机名 `LOCALHOST`，只绑定 `127.0.0.1:15497→1433`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测完删除，镜像保留。用户库 `q11s` 在删除容器前已 `DROP DATABASE`。`DROP/CREATE` 会改变 `sysobjects.id`（`1977058079` 变为 `2057058364`）。`TRUNCATE` 不改变对象 ID、`schema_ver`、`crdate` 或 `sysstat`。堆表和聚集索引的 `first`/`root`/`FirstIAM` 在空表上为空，`INSERT` 后分配，截断后再插入会复用同一组页指针。因此没有实现身份、整数键或治理链。不能套 SQL Server 2022。见[本轮记录](../reviews/2026-09-29-quality-sqlserver2000-key-coverage.md)。#11 不能关闭。

**2026-09-29 #11 接续：GBase 8a 8.6.2.43-R7-free 没有可证明的截断代际，未发凭证。** 第三方镜像 `shihd/gbase8a:1.0`，digest `sha256:3153223ce9039748d7d4400a39768c4a9ce6df39576cd34638ac58a0679a6b17`。`VERSION()` 为 `8.6.2.43-R7-free.110605`。一次性容器 `q11-gbase8a-20260929` 只绑定 `127.0.0.1:15496→5258`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，使用镜像自带 userdata，测完删除，镜像保留。`DROP/CREATE` 会改变 `information_schema.TABLES.TABLE_ID`（6 变为 7；另一轮 31 变为 51）。`TRUNCATE` 不改变 `TABLE_ID` 和 `CREATE_TIME`。`SCN`、`UPDATE_TIME` 和 `MAX_ROWID` 在 `INSERT` 上就会变。`information_schema.FILES` 与 `gbase.table_distribution` 在这台单节点上都是 0 行。因此没有实现身份、整数键或治理链。不能套 MySQL、Doris、OceanBase 或 TiDB。见[本轮记录](../reviews/2026-09-29-quality-gbase8a-key-coverage.md)。#11 不能关闭。

**2026-09-29 #11 接续：神通 V8.0.251217 永久 heap 已有原生身份和整数复测，整项仍未完成。** 社区镜像 `shentongdata/shentongdb:251217-825.2-linux64`，digest `sha256:22cda87f4e9d351c9f9e09860b7a4cdc687adf7c4042e1b4ff25deedac28c6a6`，不是官网镜像。`VERSION()` 全文是 `神通数据库管理系统V8.0.251217 for Linux(x86 64bit) (2000 connections) (license invalid after 180 days)`。一次性容器 `q11-oscar-20260929` 只绑定 `127.0.0.1:15495→2003`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测完删除，镜像保留。协议 `OSCAR_V8_0_251217_HEAP_V1` 只接受 `INFO_SCHEM.SYS_CLASS` 里的永久 heap。写入和更新不改变指纹，TRUNCATE 改变 `RELSID`，同名重建同时改变 OID 和 `RELSID`。指纹含 `USER`。只读用户能独立证明，但不能复用属主指纹；撤销 `INFO_SCHEM.SYS_POLICY` 的 SELECT 后 JDBC SQLSTATE 为 `42000`，该状态也用于语法错误，测试只要求它不是 `0A000`。拒绝视图、两种全局临时表、分区父表、非默认段、规则、外部表、本地临时表和行策略。物化视图、索引组织表、列存、`UNLOGGED`、压缩、继承、簇和嵌套表在本构建上建不成。整数单键沿用 `SHA256_SORTED_INT64_V1`，复合键 `SHA256_SORTED_OSCAR_INT_TUPLE_V1`，机构 `OSCAR_SIGNED_INT64_DECIMAL_V1`。只证明 signed int2/int4/bigint 与 NOT_NULL。数字 `0` 与空串比较相等，空串不是 NULL，NOT_NULL 先 `CAST` 成 `VARCHAR(40)`。`tinyint` 的 JDBC 类型是 -6，不收成整数。普通标识符折叠为大写后加双引号。`parseBound` 仍拒绝，这不是 #17 精确表定位。5 类 140 项，0 失败/错误/跳过，其中实库 22 项。见[本轮记录](../reviews/2026-09-29-quality-oscar-key-coverage.md)。物理恢复代际、文本键、其他规则和其他产品仍待。不能套 Oracle、达梦、PostgreSQL 或金仓。#11 不能关闭。

**2026-09-29 #11 接续：人大金仓 V008R006C009B0014 永久 heap 已有原生身份和整数复测，整项仍未完成。** 社区镜像 `huas/kingbase:v008r006c009b0014_single_x86_v1`，digest `sha256:c9e9fdb309b6b18f022e16a8cc4ea91108bf1e609e3ac134e0050a82a01ed5d9`，不是官网镜像。`version()` 全文以 `KingbaseES V008R006C009B0014` 开头，`server_version=12.1` 只是兼容见证。`database_mode=oracle`，`enable_ci=on`。一次性容器 `q11-kingbase-20260929` 只绑定 `127.0.0.1:15494→54321`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测完删除。协议 `KINGBASE_V008R006C009B0014_ORACLE_CI_HEAP_V1` 只接受永久 heap。写入和更新不改变指纹，TRUNCATE 改变 `relfilenode`，同名重建改变 OID。指纹绑定当前用户。只读角色能独立证明，但不能复用属主指纹；撤销 `pg_policy` 的 PUBLIC SELECT 后得到 `42501`。拒绝视图、物化视图、UNLOGGED、分区、继承、fillfactor、规则、临时表、全局临时表、`file_fdw` 外部表和 RLS。安全标签提供程序没有加载，不能写成已建标签被拒绝。整数单键沿用 `SHA256_SORTED_INT64_V1`，复合键 `SHA256_SORTED_KINGBASE_INT_TUPLE_V1`，机构 `KINGBASE_SIGNED_INT64_DECIMAL_V1`。只证明 signed int2/int4/int8 与 NOT_NULL；`tinyint` 的 JDBC 类型是 -6，不收成整数。普通标识符按给定拼写加双引号，不在引用时折叠。`parseBound` 仍拒绝，这不是 #17 精确表定位。5 类 140 项，0 失败/错误/跳过，其中实库 22 项。见[本轮记录](../reviews/2026-09-29-quality-kingbase-key-coverage.md)。物理恢复代际、文本键、其他规则、`enable_ci=off` 和其他 `DB_MODE` 仍待。不能套 PostgreSQL、瀚高或达梦。#11 不能关闭。

**2026-09-29 #11 接续：Sybase ASE 16.0 SP02 PL02 没有可证明的截断代际，未发凭证。** 第三方镜像 `super169/sybase:16.0-sp02`，digest `sha256:dd77cc415bdc464d2f0fbcc6619ce9ebd6d43777eda5f3d50431d487cbe0a18c`。`@@version` 与 #17 相同。一次性容器 `q11-sybase-20260929` 只绑定 `127.0.0.1:15493→5000`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测完删除。`DROP/CREATE` 会改变 `sysobjects.id`。`TRUNCATE` 不改变对象 ID、分区 ID、`datoampage`、`firstpage`、`schemacnt`、`versionts` 或 `systabstats` 计数；多页堆表的根页有一次变化，单页堆表和聚集索引不变，页号还会被复用。`TOP n` 可执行，`TOP (n)` 报 `14216`，不能套 SQL Server。因此没有实现身份、整数键或治理链。见[本轮记录](../reviews/2026-09-29-quality-sybase-key-coverage.md)。#11 不能关闭。

**2026-09-29 #11 接续：达梦 V8 / DB Version 0x7000d 普通永久行表已有原生身份和整数复测，整项仍未完成。** 第三方镜像 `yhl452493373/dm8:dm8_20260710_x86_rh7_64`，digest `sha256:3453bae780f6da9d20cca9e115e3635b4fb69728d2c085d4a50615012c5f6d4a`，不是官网镜像。`V$VERSION` 必须同时是 `DM Database Server 64 V8` 与 `DB Version: 0x7000d`。一次性容器 `q11-dm-20260929` 只绑定 `127.0.0.1:15492→5236`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测完删除。`CASE_SENSITIVE=1`：未加引号名称存成大写。协议 `DM_8_0x7000d_ROW_V1` 只接受 `INFO3=1157425104234283008` 的普通永久行表。身份含 `USER`、仅在历史行一致时纳入的打开 magic，以及 `INFO4` 低 32 位；不写死实例 magic，也不纳入 LOB 高 32 位。写入不改变指纹，TRUNCATE 与同名重建会改变。无 `SELECT ANY TABLE` 时目录读取为 `28000/-5504`；授予后读者指纹与属主不同。拒绝视图、全局临时表、HUGE、RANGE/HASH/LIST 分区、物化视图和加引号大小写名。外部表未建成，不能写成已测。整数单键沿用 `SHA256_SORTED_INT64_V1`，复合键 `SHA256_SORTED_DM_INT_TUPLE_V1`，机构 `DM_SIGNED_INT64_DECIMAL_V1`。只证明 TINYINT/SMALLINT/INTEGER/BIGINT 的整数见证与 NOT_NULL。驱动 `DmJdbcDriver18` 8.1.3.140；治理侧按可信绑定加载。普通标识符折叠为大写后加双引号，内层别名使用同一引用。`parseBound` 仍拒绝，这不是 #17 精确表定位。6 类 172 项，0 失败/错误/跳过，其中实库 24 项。见[本轮记录](../reviews/2026-09-29-quality-dm-key-coverage.md)。物理恢复代际、文本键、其他规则和其他产品仍待。不能套 Oracle、PostgreSQL 或瀚高。#11 不能关闭。

**2026-09-29 #11 接续：瀚高 6.0.4.2 永久 heap 已有原生身份和整数复测，整项仍未完成。** 镜像 `qiuchenjun/hgdb-ee:6.0.4.2_3.1.0`，digest `sha256:dd7e7fe38d1ed3672196ad2ad13fc02790b46a7baf740e3988baa3125043e961`。`pg_catalog.version()` 前缀 `HighGo Database 6 Release 6.0.4.2`，`server_version=12.7` 只是本构建的兼容见证，不调用 PostgreSQL 身份。一次性容器 `q11-highgo-20260929` 只绑定 `127.0.0.1:15491→5866`，4 GiB / 2 CPU / shm 256 MiB，无特权、无宿主目录，测完删除。协议 `HIGHGO_6_0_4_2_HEAP_V1` 只接受永久 heap 且 `reloptions` 为空、没有继承、规则、RLS 或 `pg_class` 安全标签的普通表。写入不改变指纹，TRUNCATE 与同名重建会改变。指纹绑定 `current_user`。只读角色能独立证明，但不能复用属主指纹；撤销 `pg_policy` 的 PUBLIC SELECT 后得到 `42501`。拒绝视图、UNLOGGED、分区、继承、fillfactor、规则、临时表和 RLS。整数单键沿用 `SHA256_SORTED_INT64_V1`，复合键 `SHA256_SORTED_HIGHGO_INT_TUPLE_V1`，机构 `HIGHGO_SIGNED_INT64_DECIMAL_V1`。只证明 signed int2/int4/int8 与 NOT_NULL。驱动是独立 jar `HgdbJdbc-6.2.4.jar`（清单 `6.2.0-SNAPSHOT`），经可信绑定加载，不在模块 classpath。普通标识符用双引号引用；`parseBound` 仍拒绝，这不是 #17 精确表定位。5 类 139 项，0 失败/错误/跳过，其中实库 21 项。见[本轮记录](../reviews/2026-09-29-quality-highgo-key-coverage.md)。物理恢复代际、文本键、其他规则和其他产品仍待。不能套 PostgreSQL 或 Greenplum。#11 不能关闭。

**2026-09-29 #11 接续：Greenplum 7.1.0 永久 heap hash 已有原生身份和整数复测，整项仍未完成。** 镜像 `woblerr/greenplum:7.1.0`，digest `sha256:3b3a54fe3784e4031f22a143bb8e4f630d35eacbd51b2bdb04dd786c9eab7784`。`version()` 前缀 `PostgreSQL 12.12 (Greenplum Database 7.1.0 build dev)`，`gp_server_version` 为 `7.1.0 build dev`。一次性容器 `q11-greenplum-20260929` 只绑定 `127.0.0.1:15490→5432`，4 GiB / 2 CPU，无特权、无宿主目录，测完删除。协议 `GREENPLUM_7_1_HEAP_HASH_V1` 只接受永久 heap、非空 hash 分布且 segment 数与当前 up primary 一致的普通表。写入不改变指纹，TRUNCATE 与同名重建会改变。指纹绑定 `current_user`。只读角色能独立证明，但不能复用属主指纹；撤销 `gp_distribution_policy` 的 PUBLIC SELECT 后得到 `42501`。拒绝视图、随机、复制、AO 行/列存、分区、外部表、临时表和 RLS。整数单键沿用 `SHA256_SORTED_INT64_V1`，复合键 `SHA256_SORTED_GREENPLUM_INT_TUPLE_V1`，机构 `GREENPLUM_SIGNED_INT64_DECIMAL_V1`。只证明 signed int2/int4/int8 与 NOT_NULL。普通标识符用双引号引用；`parseBound` 仍拒绝，这不是 #17 精确表定位。5 类 139 项，0 失败/错误/跳过，其中实库 21 项。见[本轮记录](../reviews/2026-09-29-quality-greenplum-key-coverage.md)。物理恢复代际、文本键、其他规则和其他产品仍待。#11 不能关闭。

**2026-09-29 #17 最新接续：MySQL 8.4.8 与 MariaDB 11.4.13 的精确表定位已证明。** 这不是新的投影方言，两者的受限 SELECT 此前已经启用。本轮只把 `parseBound` 和 `parseTableReference` 从仅 PostgreSQL 扩到这两个产品。官方镜像 `mysql:8.4`，镜像 ID `sha256:da906917ca4ace3ba55538b7c2ee97a9bc865ef14a4b6920b021f0249d603f3d`，`VERSION()` 为 `8.4.8`，`@@lower_case_table_names=0`。官方镜像 `mariadb:11.4`，镜像 ID `sha256:70cc072b29b4a89ae07abb2d4da2c64678a7f2dfe092751bb51c87d67dc1338b`，`VERSION()` 为 `11.4.13-MariaDB-ubu2404`，同样是 `lower_case_table_names=0`。一次性容器 `q17-mysql-bind-20260929` 只绑定 `127.0.0.1:15488→3306`，`q17-mariadb-bind-20260929` 只绑定 `127.0.0.1:15489→3306`，各 4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测试后删除。不带引号的库名和表名按原文保留，不折叠。`Citizen` 与 `citizen` 是不同的表。会话切到同名诱饵库后，未限定的 `citizen` 读到诱饵行；`parseBound` 使用调用方给出的库，反引号限定名读回原表。显式写出另一个库也只命中那个库。输出只用反引号，不猜测 `ANSI_QUOTES`。Doris、TiDB、StarRocks、GBase 8a 的 `parseBound` 仍拒绝。`RestrictedProjectionMySqlTest` 与 `RestrictedProjectionMariaDbTest` 各 **2/0/0**（2026-09-29T12:50:28+08:00）。不连库 `RestrictedSelectGuardTest` **12/0/0**，`MysqlTableReferenceTest` **18/0/0**，`DorisRelationIdentityTest` **15/0/0**。`python tools/quality/run_all.py` 通过（5082 个 Java 文件）。未改 `physicalName`、`quotePhysicalIdentifier` 的产品范围、`isLikePostgres()`、`isLikeSqlServer()`。无平台 SQL。受限投影仍是 25 个产品。MongoDB 与 Elasticsearch 的产品驱动仍不执行改写后的 SELECT。#17 整项未完成。

**2026-09-29 #17 最新接续：SQL Server 2000 8.00.760 SP3 成为第 25 个启用产品。** 没有官方容器镜像。`rsmoorthy/sql2000` 仍是 containerd v2.1 不接受的 v1 manifest。第三方镜像 `shirleyqe/mssql2000`，摘要 `sha256:4125eb71fcd57ddf8de8e713b31d0c4d399f8fd785ce7af148ec32563cf90b88`，基于 MSDE 2000 Release A。`SELECT @@version` 为 `Microsoft SQL Server  2000 - 8.00.760 (Intel X86) Dec 17 2002`，`SERVERPROPERTY('ProductLevel')` 为 `SP3`，版本 `Desktop Engine`。一次性容器 `q17-sql2000-20260929` 只绑定 `127.0.0.1:15487→1433`，主机名 `LOCALHOST`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测试后删除。库 `master`，属主 `dbo`。排序 `SQL_Latin1_General_CP1_CI_AS`。不带引号大小写不敏感。这条 JDBC 连接默认 `quoted_identifier` 已打开；`QUOTED_IDENTIFIER ON` 时双引号是标识符，`OFF` 时双引号是字符串（`"secret"` 返回字面量 `secret`，不是列值）。只证明 `ON`。方括号在两种设置下都是标识符。反引号是语法错误。反斜杠不是转义：`'a\b'` 长度为 3，`'a\\b'` 长度为 4，`'it''s'` 长度为 4。`TOP` 能执行，`LIMIT` 不能。一次插入多行 `VALUES` 是语法错误。仅大小写不同的列被服务器拒绝（`Column name 'id' is specified more than once`）。SQL Server 2000 没有独立 schema，表建在 `dbo` 下。`dialect()` 使用 Druid `sqlserver`，与 SQL Server 2022 同一套大小写不敏感解析。模块依赖的 `mssql-jdbc` 13.4.0.jre11 以及 6.4/7.2/9.2/10.2/12.10 都拒绝 server major version 8；JDBC Driver 4.0 起就不支持 SQL Server 2000。同一类名 `com.microsoft.sqlserver.jdbc.SQLServerDriver` 的 JDBC Driver 3.0.1301.101（`sqljdbc4` 3.0，字节码门槛是 major version 大于等于 8）能执行改写后的 SELECT。该 jar 经 `JarClassLoader` 加载，未改 POM/BOM，避免和 13.4 的同名类冲突。`RestrictedProjectionSqlServer2000Test` **1/0/0**（2026-09-29T12:22:44+08:00）。不连库 `RestrictedSelectProjectionTest` **30/0/0**，`RestrictedSelectGuardTest` **11/0/0**。`SqlStatementClassifier` 与 `SqlExecutionPlanService` 仍拒绝 SQL Server 2000，未改它们的 `dialect()`。`python tools/quality/run_all.py` 通过（5082 个 Java 文件）。未改 `parseBound` / `parseTableReference` / `parseRelationReference` / `quotePhysicalIdentifier` / `isLikeSqlServer()`。无平台 SQL。已启用 25 个产品，其余 2 个未证明：MongoDB、Elasticsearch。这两个产品驱动的字节码里没有执行改写后 SELECT 的路径，#17 整项未完成。

**2026-09-29 #17 最新接续：优炫 UXsinoDB 2.1.0.3 成为第 24 个启用产品。** 没有厂商公开镜像。Docker Hub 上可用的 amd64 第三方镜像里，`starsonic/uxdb-centos7.4`（摘要 `sha256:275032f72544605a57070a98760b5251f839822f33816bb90c0d8024dc64d75e`）是编译机残留：历史记录显示源码树和安装目录在提交前已删除，镜像里没有服务端。`caimsdocker/uxsino_uxsql` 是 AFL 编译环境，不是数据库。`caimsdocker/uxdb:1.0`（摘要 `sha256:5ee70dd8dbfd057b38f66ce7a9dab3cb11de181affc3a789fa040f24b79cfb72`）带有服务端。`uxdb --version` 与 `SELECT version()` 都是 `UXsinoDB 2.1.0.3 on x86_64-pc-linux-gnu, compiled by gcc (GCC) 7.3.0, 64-bit`。数据目录 `UX_VERSION` 为 10。一次性容器 `q17-uxdb-20260929` 只绑定 `127.0.0.1:15486→5432`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测试后删除。用户 `uxdb`，库 `uxdb`，schema `public`。镜像自带许可文件，启动日志写明已加载；同时有 `Software information matching failed`（容器里没有 `dmidecode`），查询仍可执行。不带引号折叠为小写，双引号按原文，同一张表可以同时有 `Id` 和 `id`。服务器会剥掉反引号，里面的文本按未加引号解析：`` `ID` `` 命中 `id`，`` `.*` `` 变成语法错误。默认 `standard_conforming_strings=on` 时反斜杠是字面量，`off` 时是转义。`'it''s'` 长度为 4。`dialect()` 使用 Druid `postgresql` 词法。`isLikePostgres()` 原本已包含优炫，未改。产品驱动是 `com.uxsino.uxdb.Driver`（`uxdbjdbc` 4.2，来自 `starsonic/uxdb-centos7.4` 里的本地 Maven 缓存，经 `JarClassLoader` 加载，未改 POM/BOM）。镜像原认证是 md5。`RestrictedProjectionUxdbTest` **1/0/0**（2026-09-29T11:58:20+08:00）。不连库 `RestrictedSelectProjectionTest` **29/0/0**。`python tools/quality/run_all.py` 通过（5081 个 Java 文件）。未改 `parseBound` / `parseTableReference` / `parseRelationReference` / `quotePhysicalIdentifier`。无平台 SQL。已启用 24 个产品，其余 3 个未证明：SQL Server 2000、MongoDB、Elasticsearch。#17 整项未完成。

**2026-09-29 #17 最新接续：Sybase ASE 16.0 SP02 PL02 成为第 23 个启用产品。** 没有厂商公开镜像。第三方镜像 `super169/sybase:16.0-sp02`，摘要 `sha256:dd77cc415bdc464d2f0fbcc6619ce9ebd6d43777eda5f3d50431d487cbe0a18c`。`SELECT @@version` 为 `Adaptive Server Enterprise/16.0 SP02 PL02/EBF 25320 SMP/P/x86_64/Enterprise Linux/ase160sp02plx/2492/64-bit/FBO/Sat Nov 21 04:05:39 2015`。服务器名 `MYSYBASE`。一次性容器 `q17-sybase-20260929` 只绑定 `127.0.0.1:15485→5000`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测试后删除。排序是 `bin_iso_1`。带引号和不带引号的名字都按原文，`ID` 和 `Id` 不命中 `id`，同一张表可以同时有 `Id` 和 `id`。`quoted_identifier off` 时双引号是字符串；`on` 时双引号是标识符。方括号始终是标识符。反引号是语法错误。反斜杠不是转义，`'it''s'` 长度为 4。`TOP` 能执行，`LIMIT` 不能。`dialect()` 使用 Druid `sqlserver` 词法，不用 `sybase`：后者拒绝 `TOP`，却输出服务器不执行的 `LIMIT`。名字解析按原文精确匹配，不走 SQL Server 2022 的大小写不敏感。`HOLDLOCK` 被该词法收成表别名，守卫在重写时去掉别名，锁不会送到服务器。产品驱动是镜像里的 `com.sybase.jdbc4.jdbc.SybDriver`（`jConnect-16_0/classes/jconn4.jar`，经 `JarClassLoader` 加载，未改 POM/BOM）。库用 `tempdb`，属主 `dbo`。只证明 `quoted_identifier on`。`RestrictedProjectionSybaseTest` **1/0/0**（2026-09-29T11:48:09+08:00）。不连库 `RestrictedSelectProjectionTest` **28/0/0**。`python tools/quality/run_all.py` 通过（5080 个 Java 文件）。未改 `parseBound` / `parseTableReference` / `parseRelationReference` / `quotePhysicalIdentifier` / `isLikePostgres()`。无平台 SQL。已启用 23 个产品，其余 4 个未证明：SQL Server 2000、UXDB、MongoDB、Elasticsearch。#17 整项未完成。

**2026-09-29 #17 最新接续：GBase 8a 8.6.2.43-R7-free 成为第 22 个启用产品，瀚高 6.0.4.2 成为第 21 个。** 两者都没有厂商公开的官方镜像。按本次要求使用第三方镜像，启用条件仍是 `version()`/`VERSION()` 识别该产品，并且该产品自己的 JDBC 执行改写后的 SELECT。瀚高镜像 `qiuchenjun/hgdb-ee:6.0.4.2_3.1.0`，摘要 `sha256:dd7e7fe38d1ed3672196ad2ad13fc02790b46a7baf740e3988baa3125043e961`。`SELECT version()` 为 `HighGo Database 6 Release 6.0.4.2-64-bit Production`。`server_version=12.7` 只是兼容层，不据此从 PostgreSQL 外推。镜像内没有 `hgdb.lic`，首次初始化由服务器写入试用许可，日志写明试用到 2027-09-29。一次性容器 `q17-hg-20260929` 只绑定 `127.0.0.1:15483→5866`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，测试后删除。用户 `highgo`，库 `highgo`。不带引号折叠为小写，双引号按原文；反引号同样按原文，同一张表可以同时有 `Id` 和 `id`。`standard_conforming_strings=on` 时反斜杠是字面量，`off` 时是转义。`'it''s'` 长度为 4。`dialect()` 使用 Druid `postgresql` 词法，不用 `highgo`。产品驱动是 `com.highgo.jdbc.Driver`（`HgdbJdbc` 6.0.6.jre8，经 `JarClassLoader` 加载，未改 BOM）。`isLikePostgres()` 未改。`RestrictedProjectionHighGoTest` **1/0/0**（2026-09-29T09:59:02+08:00）。GBase 8a 镜像 `shihd/gbase8a:1.0`，摘要 `sha256:3153223ce9039748d7d4400a39768c4a9ce6df39576cd34638ac58a0679a6b17`，对应 NoLicense-Free 8.6.2 build 43。`SELECT VERSION()` 为 `8.6.2.43-R7-free.110605`，`version_comment` 含 `GBase`。一次性容器 `q17-gbase8a-20260929` 只绑定 `127.0.0.1:15484→5258`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录，使用镜像自带 userdata，测试后删除。`lower_case_table_names=1`。列名大小写不敏感，仅大小写不同的列被服务器拒绝。反引号是标识符。默认 `sql_mode` 含 `ANSI_QUOTES`，双引号是标识符；去掉后双引号是字符串。`NO_BACKSLASH_ESCAPES` 会关闭反斜杠转义。`'it''s'` 长度为 4。普通表引擎是 `EXPRESS`。`dialect()` 使用 MySQL 词法，不用 Druid `gbase`：后者把反引号重写成双引号，`ANSI_QUOTES` 关闭时列名会变成字符串。产品驱动是本机已有的 `com.gbase.jdbc.Driver`（`gbase-connector-java` 8.3.81.53，经 `JarClassLoader` 加载，未改 POM/BOM）。`RestrictedProjectionGbase8aTest` **1/0/0**（同一时刻）。不连库 `RestrictedSelectProjectionTest` **27/0/0**。`python tools/quality/run_all.py` 通过（5079 个 Java 文件）。未改 `parseBound` / `parseTableReference` / `parseRelationReference` / `quotePhysicalIdentifier`。无平台 SQL。已启用 22 个产品，其余 5 个未证明：SQL Server 2000、Sybase、UXDB、MongoDB、Elasticsearch。优炫没有可拉取的公开镜像，厂商安装包要求许可证后实例才会启动，本地也没有 `com.uxsino.uxdb.Driver`。SQL Server 2000：`rsmoorthy/sql2000` 仍是 containerd v2.1 不再接受的 v1 manifest，拉取失败。第三方重建镜像 `shirleyqe/mssql2000`（摘要 `sha256:4125eb71fcd57ddf8de8e713b31d0c4d399f8fd785ce7af148ec32563cf90b88`）在无特权容器里打开了 1433，但模块使用的 `mssql-jdbc` 13.4.0.jre11 拒绝 SQL Server 8。没有改用其他驱动。容器 `q17-sql2000-20260929` 已删除。#17 整项未完成。

**2026-09-29 #17 最新接续：神通 V8.0.251217 成为第 20 个启用产品。** 社区镜像 `shentongdata/shentongdb:251217-825.2-linux64`，摘要 `sha256:22cda87f4e9d351c9f9e09860b7a4cdc687adf7c4042e1b4ff25deedac28c6a6`。`SELECT VERSION()` 为神通数据库管理系统 V8.0.251217，并提示许可 180 天后失效。入口在没有 `SHENTONG_LICENSE` 时复制镜像自带的试用许可文件。一次性容器只绑定 `127.0.0.1:15482→2003`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录。用户 `SYSDBA`，库 `OSRDB`。不带引号折叠为大写，双引号按原文：`id` 和 `"ID"` 命中 `ID`，`"Id"` 不命中 `ID`。反引号也是按原文的标识符，`` `ID` `` 命中 `ID`，`` `id` `` 不命中。反斜杠不是转义，`'it''s'` 长度为 4。`LIMIT`、`ROWNUM`、`FETCH FIRST` 都能执行。服务器允许同一张表同时有 `Id` 和 `ID`；守卫仍像 Oracle 一样在入口拒绝仅大小写不同的列元数据。`dialect()` 使用 Druid `oscar`。神通解析器把 `FOR SHARE` 放在自己的 `ForClause` 上，守卫单独拒绝。产品驱动是 `com.oscar.Driver`（本地 `oscar-jdbc` 7.0.0，经 `JarClassLoader` 加载，未改 BOM）。未改 `parseBound` / `parseTableReference` / `parseRelationReference` / `quotePhysicalIdentifier`。`RestrictedProjectionOscarTest` **1/0/0**（2026-09-29T09:46:30+08:00）。不连库 `RestrictedSelectProjectionTest` **25/0/0**。`python tools/quality/run_all.py` 通过（5077 个 Java 文件）。无平台 SQL。已启用 20 个产品，其余 7 个未证明。#17 整项未完成。

**2026-09-29 #17 最新接续：人大金仓 V008R006C009B0014 成为第 19 个启用产品。** 社区转存镜像 `huas/kingbase:v008r006c009b0014_single_x86_v1`，摘要 `sha256:c9e9fdb309b6b18f022e16a8cc4ea91108bf1e609e3ac134e0050a82a01ed5d9`。`SELECT version()` 为 `KingbaseES V008R006C009B0014 on x86_64-pc-linux-gnu, compiled by gcc (GCC) 4.8.5 20150623 (Red Hat 4.8.5-28), 64-bit`。一次性容器只绑定 `127.0.0.1:15481→54321`，4 GiB / 2 CPU / shm 1 GiB，无特权、无宿主目录。`DB_MODE=oracle`，用户 `system`，库 `test`，schema `public`。初始化日志写明字符串比较不区分大小写。不带引号的列存成小写；带引号的列保留目录大小写，查询时带引号和不带引号都不区分大小写。仅大小写不同的列被服务器拒绝。反引号不是标识符。`standard_conforming_strings` 的 on/off 都测过。`dialect()` 使用 Druid `postgresql` 词法；名字解析不按 PostgreSQL 精确匹配带引号的名字，改为大小写不敏感的唯一匹配。产品驱动是模块里的 `com.kingbase8.Driver` 9.0.1.jre7。未改 `parseBound` / `parseTableReference` / `parseRelationReference` / `quotePhysicalIdentifier` / `isLikePostgres()`。`RestrictedProjectionKingbaseTest` **1/0/0**（2026-09-29T09:39:11+08:00）。不连库 `RestrictedSelectProjectionTest` **24/0/0**。`python tools/quality/run_all.py` 通过（5076 个 Java 文件）。`ENABLE_CI=no` 和其他 `DB_MODE` 未证明。无平台 SQL。已启用 19 个产品，其余 8 个未证明。#17 整项未完成。

**2026-09-29 #17 最新接续：达梦 V8 成为第 18 个启用产品。** 镜像 `yhl452493373/dm8:dm8_20260710_x86_rh7_64`，摘要 `sha256:3453bae780f6da9d20cca9e115e3635b4fb69728d2c085d4a50615012c5f6d4a`。这是带开发许可的第三方封装，许可到期日 2027-07-07，不是达梦官网镜像。`V$VERSION` 为 `DM Database Server 64 V8`，库版本 `0x7000d`，构建号 `03134284604-20260707-335949-20228`。一次性容器只绑定 `127.0.0.1:15480→5236`，4 GiB / 2 CPU / shm 1 GiB，无宿主目录；镜像里的数据目录不存在，启动时先建目录再降权到 `dmdba`。`dminit CASE_SENSITIVE=1`，`GLOBAL_CHARSET=1`。不带引号折叠为大写，双引号按原文：未加引号的 `id` 命中 `ID`，`"Id"` 命中单独的列 `Id`，`"ID"` 命中 `ID`。反斜杠不是转义，`'it''s'` 长度为 4。`LIMIT`、`ROWNUM`、`FETCH FIRST` 都能执行。`dialect()` 使用 Druid `dm`，名字解析与 Oracle/DB2 一样把不带引号的标识符折叠为大写。产品驱动是模块里的 `dm.jdbc.driver.DmDriver` 8.1.3.140，用户 `SYSDBA`。未改 `parseBound` / `parseTableReference` / `parseRelationReference` / `quotePhysicalIdentifier`。`RestrictedProjectionDmTest` **1/0/0**。不连库 `RestrictedSelectProjectionTest` **23/0/0**。`python tools/quality/run_all.py` 通过（5075 个 Java 文件）。`CASE_SENSITIVE=0` 未证明。无平台 SQL。已启用 18 个产品，其余 9 个未证明。#17 整项未完成。

**2026-09-29 #17 最新接续：Greenplum 7.1.0 成为第 17 个启用产品。** 社区镜像 `woblerr/greenplum:7.1.0`（GPDB 本体，不是 Greengage / WarehousePG / OpenGPDB），摘要 `sha256:3b3a54fe3784e4031f22a143bb8e4f630d35eacbd51b2bdb04dd786c9eab7784`。`SELECT version()` 为 `PostgreSQL 12.12 (Greenplum Database 7.1.0 build dev)`，`gp_server_version` 为 `7.1.0 build dev`。一次性容器只绑定 `127.0.0.1:15479→5432`，4 GiB / 2 CPU，单容器里有协调节点和两个 segment，无宿主目录，测完删除。普通表可以不写 `DISTRIBUTED BY`，服务器自选分布键并给出 NOTICE。不带引号的标识符折叠为小写；双引号按原文，`"Id"` 不等于 `id`，同一张表可以同时存在这两列。反引号不是标识符。默认 `standard_conforming_strings=on` 时反斜杠是普通字符，`off` 时反斜杠是转义；`'it''s'` 长度为 4。`dialect()` 把 `GREENPLUM` 放进 postgresql 分支，名字折叠沿用已有的 `isLikePostgres()`。产品驱动是 `org.postgresql.Driver`。未改 `parseBound` / `parseTableReference` / `parseRelationReference` / `quotePhysicalIdentifier`。`RestrictedProjectionGreenplumTest` **1/0/0**。不连库 `RestrictedSelectProjectionTest` **22/0/0**、`RestrictedSelectGuardTest` **11/0/0**。`python tools/quality/run_all.py` 通过（5074 个 Java 文件）。外部表和多机拓扑未证明。无平台 SQL。已启用 17 个产品，其余 10 个未证明。#17 整项未完成。

**2026-09-29 #17 最新接续：MongoDB 7.0.43 与 Elasticsearch 8.15.3 仍不启用。** 官方镜像 `mongo:7.0`，摘要 `sha256:9854f7139445d766a9523571d6f047530c45547460ffcf8259eb2bf4264632ca`，`db.version()` 为 **7.0.43**。一次性容器只绑定 `127.0.0.1:15477→27017`，测完删除。服务器没有 `sql` 命令。产品驱动 `com.gitee.jdbc.mongodb.JdbcDriver` 把 `SELECT id, name FROM ...` 当 JavaScript，报 `Expected ; but found id`。原生 `find` 能按字段投影，但那不是本守卫改写的 SQL。官方镜像 `docker.elastic.co/elasticsearch/elasticsearch:8.15.3`，摘要 `sha256:01c1732062b4a846c5ca687b0094b89bad0bfed00c6d71626db32fb8f3131a78`，build `f97532e680b555c3a05e73a74c28afb666923018`，basic 许可。一次性容器只绑定 `127.0.0.1:15478→9200`，关闭认证和 GeoIP 下载，测完删除。`POST /_sql` 能执行 `SELECT id, name FROM citizen`。不带引号和双引号都按原文，`Id` 不等于 `id`，`Citizen` 不等于 `citizen`。反引号被服务器拒绝。产品 Jest 驱动 `jdbc-jest-driver-1.0.0` 把 SQL 原文当 JSON 提交，服务器报 `Unrecognized token 'SELECT'`。组件没有调用 `/_sql`。官方 JDBC 需要订阅许可，未启用试用。`dialect()` 继续拒绝这两个产品。无平台 SQL。已启用仍是 16 个，其余 11 个未证明。#17 整项未完成。

**2026-09-29 #17 最新接续：Hive 4.1.0 成为第 16 个启用产品。** 官方镜像 `apache/hive:4.1.0`，摘要 `sha256:b957bb0e698e560d24e162b7549d79f191d58703e92899a886618c317f6e8f30`，`SELECT version()` 为 `4.1.0 r75e40b7537c91a70ccaa31c397d21823c7528eeb`。一次性容器只绑定 `127.0.0.1:15476→10000`，内嵌 Derby，Tez local，4 GiB / 2 CPU，无宿主目录，未启动已有的 `srt-hive-*`。普通文本表：反引号是标识符；不带引号和反引号都不区分大小写，建表后列名记成小写；仅大小写不同的列被服务器拒绝（Error 10036）。默认 `hive.support.quoted.identifiers=column` 时双引号是字符串；`standard` 时双引号是列名；`none` 时反引号变成正则，点星模式会选出未授权列。守卫使用 MySQL 词法，不用 Druid `hive`：后者把双引号收成字符串，并保留未做列授权检查的 `SORT BY` / `CLUSTER BY` / `DISTRIBUTE BY`。双引号因此在授权前被拒绝。差异测试只覆盖 `column` 和 `standard`。驱动是已有的 `hive-jdbc-4.1.0-standalone.jar`，没有改 BOM 里的 3.1.3。未改 `parseBound` / `parseTableReference` / `parseRelationReference`。`RestrictedProjectionHiveTest` **1/0/0**。不连库 `RestrictedSelectProjectionTest` **21/0/0**、`RestrictedSelectGuardTest` **11/0/0**，`SQLSERVER2000` 与 GBase 8a 继续拒绝。`python tools/quality/run_all.py` 通过（5073 个 Java 文件）。容器测试后删除。无平台 SQL。已启用 16 个产品，其余 11 个未证明。#17 整项未完成。

**2026-09-29 #17 最新接续：InfluxDB 3 Core 3.11.5 成为第 15 个启用产品。** 官方镜像 `influxdb:3-core`，git `f083f73c92eaa87186d39c81bbd8afc3a3fd8d94`。一次性容器只绑定 `127.0.0.1:15475→8181`，`--without-auth`、内存对象存储、关闭包管理。SQL 模式名是 `iox`，不是数据库名。不带引号大小写不敏感，双引号按原文，`"Id"` 不等于字段 `id`。反斜杠不是转义。服务器拒绝 `CREATE TABLE`，表由行协议写入。词法使用 PostgreSQL 精确词法以识别双引号标识符，名字解析不按 PostgreSQL 折叠。`RestrictedProjectionInfluxDb3Test` **1/0/0**，JDBC 需要 `--add-opens=java.base/java.nio=ALL-UNNAMED`。不连库 `RestrictedSelectGuardTest` 与 `RestrictedSelectProjectionTest` 仍通过，`SQLSERVER2000` 继续拒绝。`python tools/quality/run_all.py` 通过。容器测试后删除。无平台 SQL。已启用 15 个产品，其余 12 个未证明。#17 整项未完成。

**2026-09-29 #17 最新接续：DB2 12.1.5.0 成为第 14 个启用产品。** 同一镜像摘要 `sha256:2de8151713c261843868c5c3411b57be6ae79d99d70a5b3022337836776bfda6` 在 2026-09-25 无特权启动时 `db2sysc` 提前退出。本轮按 IBM 容器入口要求，只为这一个一次性容器使用 `--privileged`：4 GiB / 2 CPU / shm 1 GiB，仅 `127.0.0.1:15474→50000`，无宿主目录、无 host 网络。`db2level` 为 **12.1.5.0**，库 `Q17DB`，授权 ID `DB2INST1`。不带引号的标识符折叠为大写，双引号按原文；`'a\b'` 保持反斜杠，单引号用 `''` 转义。`dialect()` 返回 Druid `db2`。未改 `parseBound` / `parseTableReference`。`RestrictedProjectionDb2Test` **1/0/0**。不连库见证 `RestrictedSelectGuardTest`、`RestrictedSelectProjectionTest`、`SqlStatementClassifierTest` 仍通过，`SQLSERVER2000` 继续拒绝。`python tools/quality/run_all.py` 通过。容器测试后删除。无平台 SQL。已启用 14 个产品，其余 13 个未证明。#17 整项未完成。

**2026-09-29 #17 最新接续：OceanBase CE 4.3.5.6 MySQL 模式成为第 13 个启用产品。** 真实探测：`VERSION()`=`5.7.25-OceanBase_CE-v4.3.5.6`，`@@ob_compatibility_mode=MYSQL`，`lower_case_table_names=1`，用户 `root@test` 空口令。反引号是标识符。默认双引号是字符串；`ANSI_QUOTES` 把双引号改成标识符；`NO_BACKSLASH_ESCAPES` 关闭反斜杠转义。四种会话变量下反引号查询都能执行。`dialect()` 把 `OCEANBASE` 放进 mysql 分支。枚举的 `getQuote()` 仍是空，守卫在投影时对该产品使用反引号。Oracle 模式未验证，不启用。未改 `parseBound` / `parseTableReference` / `parseRelationReference`。`RestrictedProjectionOceanBaseTest` **1/0/0**。DB-less 原专项 **84/0/0/0**，`SQLSERVER2000` 与 GBase 8a 仍默认拒绝。`python tools/quality/run_all.py` 通过。容器 `q17-oceanbase-20260929`（`oceanbase/oceanbase-ce:4.3.5.6-106000012026040916`，MODE=mini，仅 `127.0.0.1:15473→2881`，8 GiB）测试后删除。无平台 SQL。已启用 13 个产品，其余 14 个未证明。#17 整项未完成。

**2026-09-29 #17 最新接续：又证明 SQLite、SQL Server 2022、TDengine、Oracle，共 12 个产品。剩余 15 个没有在本轮完成。** 每个都用了本机官方引擎，没有按家族外推。SQLite 使用 `sqlite-jdbc` 3.31.1 文件库，schema 为 `main`，带引号按原文、不带引号大小写不敏感，双引号是标识符，反斜杠不是转义。SQL Server 是 `16.0.4295.3`（2022），`QUOTED_IDENTIFIER ON`，双引号和方括号是标识符，默认排序规则不区分大小写，不用 `LIMIT`，改用 `TOP`；**SQL Server 2000 仍拒绝**。TDengine `3.3.8.8` 普通表：反引号是标识符，双引号是字符串；Druid taos 输出会把列名写成双引号，渲染时改回反引号，否则会变成字符串常量。Oracle Free `23.26.3.0.0`：不带引号折叠为大写，双引号按原文，物理列按大写绑定。四项真实库测试各 **1/0/0**。不连库回归在改见证产品为 `SQLSERVER2000` 后仍通过。`python tools/quality/run_all.py` 需以本轮最后一次为准。容器 `q17-mssql-20260928`、`q17-tdengine-20260928`、`q17-oracle-20260928` 测试后删除。无平台 SQL。

**仍不能标成已证明：** 本机没有官方服务端的是 SQL Server 2000、Greenplum、达梦、金仓、神通、GBase 8a、Sybase、瀚高、优炫。本机有官方镜像但本轮还没做差异测试的是 OceanBase、DB2、Hive、MongoDB、InfluxDB 3、Elasticsearch。这 15 个继续默认拒绝。#17 整项未完成。

**2026-09-28 #17 最新接续：受限投影支持扩到第 8 个产品 ClickHouse（独立方言，无平台 SQL）。** 真实探测确认 `clickhouse/clickhouse-server:25.8` 实际版本 `25.8.33.6`。反引号可用；未加引号的库名、表名、列名都区分大小写且不折叠；双引号在服务器上是标识符。字符串默认把反斜杠当转义。会话 `dialect` 只能是 `clickhouse` / `kusto` / `prql` / `promql`。守卫使用 Druid `clickhouse` 方言，并拒绝 `FINAL`、`PREWHERE`、`SAMPLE`、`FORMAT`、`SETTINGS`、`WITH TOTALS/FILL/TIES`。列名按元数据原样匹配，不走 MySQL 的忽略大小写。双引号整段拒绝：Druid 会把双引号当成字符串，而服务器会当成列名。输出只用反引号。`kusto`/`prql`/`promql` 下这段 SQL 解析失败、不会执行。未改 `parseBound` / `parseTableReference` / `parseRelationReference`。新增 `RestrictedProjectionClickHouseTest`（门控 `datahub.test.clickhouse.url`，用户 `default`、空口令，Atomic + MergeTree）。验证：该测试 **1/0/0**；DB-less **84/0/0/0**（原专项加上 `RestrictedSelectProjectionTest` 现为 20 项，含 ClickHouse 子句与大小写见证，GBase 8a 与 ORACLE 仍默认拒绝）；`python tools/quality/run_all.py` 通过。JDBC 缺少 Apache HttpClient 5 时驱动回退到 JDK HTTP，查询仍成功。`dialect()` 不改已启用产品路径，未重复 Supply/DataForge 套件；这两处会把数据源产品传入守卫，ClickHouse 数据源的受限查询从此不再因“尚未验证”被拒。容器 `q17-clickhouse-20260928`（仅绑 127.0.0.1:15468→8123、2 GiB、无卷）测试后已删除。见[本轮记录](../reviews/2026-09-28-quality-restricted-projection-hardening.md)。仍待其余 19 个产品，GBase 8a 仍无官方实例；#17 整项未完成。

**2026-09-28 #17 最新接续：GBase 8a 本轮不能启用。** 本机没有 GBase 8a 镜像或已运行实例，模块 POM 也没有 `com.gbase.jdbc.Driver`。官网下载中心不提供可直接拉取的服务端镜像，公开 Docker Hub 上的 `shihd/gbase8a`、`yellowcong/gbase8a`、`flobit/gbase8a` 是第三方封装的旧 Free 包，按 [2026-09-25 环境边界](../reviews/2026-09-25-quality-sqlite-native-metadata.md) 不能当作原生产品证据。`dialect()` 未加入 `GBASE8A`。`RestrictedSelectProjectionTest.unverifiedProductFailsClosed` 增加默认拒绝见证：`restrictProjection(GBASE8A, ...)` 抛出「尚未验证」。DB-less 该测试类 **19/0/0/0**（仍是 19 个测试方法）。没有新建容器，没有平台 SQL。要继续本产品，需要官方安装介质或已隔离实例（`jdbc:gbase://127.0.0.1:5258/` 一类）以及对应 JDBC 驱动，再按 Doris 同一套差异探测；不能用 MySQL/Doris 结果代替。#17 仍是 7 个产品已启用、其余 20 个未验证。

**2026-09-28 #17 最新接续：受限投影支持扩到第 7 个产品 Doris（MySQL 协议族，仍只改 DB Switch 组件、无平台 SQL）。** 真实探测确认 Doris `apache/doris:fe-4.0.8` + `be-4.0.8`（`VERSION()`=`5.7.99`，`version_comment`=`doris-4.0.8-rc02-bc8ea1bac6d`，BE Alive）走 MySQL 线协议、反引号引用、`lower_case_table_names=0`（未加引号表名区分大小写，列名不区分）。会话变量可设为 DEFAULT / ANSI_QUOTES / NO_BACKSLASH_ESCAPES / 组合。**ANSI_QUOTES 不把双引号改成标识符；NO_BACKSLASH_ESCAPES 会生效**（默认模式下 `'a\\b'` 折成一个反斜杠，该模式下保持两个）。守卫只输出反引号，并继续拒绝含单引号或反斜杠的字面值。仅在 `RestrictedSelectGuard.dialect()` 的 mysql 分支显式加入 `DORIS`。`quote()` / 非 PG 折叠 / `MySqlLexer` 此前已按 MySQL 家族处理 Doris。`parseRelationReference` 原本已含 Doris（关系名语法，不是本项投影）；`parseBound` / `parseTableReference` 仍拒绝 Doris。同族未验证的 GBASE8A 仍默认拒绝。新增真实库差异 `RestrictedProjectionDorisTest`（门控 `datahub.test.doris.url`、`root`/空口令，四种会话变量）。验证：该测试 **1/0/0**；DB-less **98/0/0/0**（原 7 个专项 83 项，另加 `DorisRelationIdentityTest` 15 项，ORACLE 默认拒绝与 Doris `parseBound`/`parseTableReference` 拒绝不变）；`python tools/quality/run_all.py` 通过。`dialect()` 对已启用产品路径是纯加法，未重复 Supply/DataForge 消费者回归；这两处已把数据源产品传入守卫，Doris 数据源的受限查询从此不再因“尚未验证”被拒。本机 FE `q17-doris-fe-20260928`（仅绑 127.0.0.1:15467→9030、2 GiB）与 BE `q17-doris-be-20260928`（无宿主机端口、4 GiB）及自有网络 `q17-doris-20260928`（172.30.17.0/24，无卷）测试后已删除，未触碰 `doris4-*` / `doris4-local-net`。见[本轮记录](../reviews/2026-09-28-quality-restricted-projection-hardening.md)（“2026-09-28 增量：Doris”节 + 产品矩阵）。仍待：第 10 节其余 20 产品逐库证明（PG 家族 4、MySQL 家族 GBASE8A 亦未验证）、`parseBound`/`parseTableReference` 扩产品、查询途中撤权/到期复验等按第 6 节未完成清单继续，不以家族外推；#17 整项仍未完成，六项顺序不变。

**2026-09-28 #17 最新接续：受限投影支持扩到第 6 个产品 StarRocks（MySQL 协议族，仍只改 DB Switch 组件、无平台 SQL）。** 真实探测确认 StarRocks 3.5.21（`current_version()`=`3.5.21-85fccf8`，`version` 变量=`8.0.33`）走 MySQL 线协议、反引号引用、`lower_case_table_names=0`（未加引号表名区分大小写，列名不区分）。会话变量可设为 DEFAULT / ANSI_QUOTES / NO_BACKSLASH_ESCAPES / 组合，但 **ANSI_QUOTES 不把双引号改成标识符，NO_BACKSLASH_ESCAPES 也不关闭反斜杠转义**；守卫只输出反引号，并继续拒绝含单引号或反斜杠的字面值，因此这两个语义差不放行本套攻击。仅在 `RestrictedSelectGuard.dialect()` 的 mysql 分支显式加入 `STARROCKS`（证据门控）。`quote()` / 非 PG 折叠 / `MySqlLexer` 此前已按 MySQL 家族处理 StarRocks。未动 `parseRelationReference` 允许名单（StarRocks 不在其中）。同族未验证的 DORIS / GBASE8A 仍默认拒绝。新增真实库差异 `RestrictedProjectionStarRocksTest`（门控 `datahub.test.starrocks.url`、`root`/空口令，四种会话变量）。验证：该测试 **1/0/0**（越权授权前全拒、合法查询四变量下真实执行且只返回批准列）；DB-less **83/0/0/0**（原 5 个专项 46 项，另加 `RestrictedSelectProjectionTest` 19 与 `MysqlTableReferenceTest` 18，ORACLE 默认拒绝与 TiDB 关系引用拒绝不变）；`python tools/quality/run_all.py` 通过。`dialect()` 对已启用产品路径是纯加法，未重复 Supply/DataForge 消费者回归（openGauss 轮已验证未过度拒绝）；DataForge `DatabaseApiImpl` 与 Supply `DataApiExecutor` 已把数据源产品传入该守卫，StarRocks 数据源的受限查询从此不再因“尚未验证”被拒。本机单容器 `q17-starrocks-20260928`（`starrocks/allin1-ubuntu:3.5.21`，仅绑 127.0.0.1:15466、无卷、`root` 空口令）测试后已 `docker rm -f`，未触碰 `doris4-*` 等他方资源。见[本轮记录](../reviews/2026-09-28-quality-restricted-projection-hardening.md)（“2026-09-28 增量：StarRocks”节 + 产品矩阵）。仍待：第 10 节其余 21 产品逐库证明（PG 家族 4、MySQL 家族 DORIS/GBASE8A 亦未验证）、`parseBound`/`parseTableReference` 扩产品、查询途中撤权/到期复验等按第 6 节未完成清单继续，不以家族外推；#17 整项仍未完成，六项顺序不变。

**2026-09-28 #17 接手点（历史排查记录，不要从这里重做）：** 用户决定 #12 暂停（恢复/克隆代际方式未定，不做），转 #17。上一会话只完成排查与测试准备，**`RestrictedSelectGuard` 生产代码未改、差异测试尚未运行**（原会话自动权限模式拦截了构建命令）。详细发现、已写文件、待清理资源与执行计划见第 6 节“2026-09-28 排查接手”。#15、#18、#19 本日各子项已完成，记录见下方各段；六项顺序中 #12 暂停，当前推进 #17，其后 #11。

**2026-09-28 #17 最新接续：受限投影支持扩到第 5 个产品 TiDB（MySQL 协议族，仍只改 DB Switch 组件、无平台 SQL）。** 真实探测确认 TiDB 走 MySQL 线协议（`jdbc:mysql://`、`version()`=`8.0.11-TiDB-v8.5.6`）、反引号引用、`sql_mode` 支持 `ANSI_QUOTES`/`NO_BACKSLASH_ESCAPES`，仅在 `RestrictedSelectGuard.dialect()` mysql 分支显式加入 `TIDB`（证据门控）；`quote()` / 折叠分支（`resolve`/`physicalName`/去重走非 PG 分支）/ 词法预扫（`MySqlLexer`）此前已按 MySQL 家族处理 TiDB，无需再改；未动 `parseRelationReference` 允许名单（TiDB 不在其中）。同族未验证的 DORIS/STARROCKS/GBASE8A 仍默认拒绝。新增真实库差异 `RestrictedProjectionTiDbTest`（门控 `datahub.test.tidb.url`、`root`/空口令，会话模式 DEFAULT/ANSI_QUOTES/NO_BACKSLASH_ESCAPES/组合）。验证：`RestrictedProjectionTiDbTest` **1/0/0**（越权授权前全拒、合法查询四模式真实执行且只返回批准列）；DB-less 回归 **46/0/0/0**、BUILD SUCCESS，ORACLE 默认拒绝见证不变——`dialect()` 改动纯加法、不改已启用产品路径，故未重复消费者回归（openGauss 轮已验证）。本机单容器 `q17-tidb-20260928`（`pingcap/tidb:v8.5.6` unistore，仅绑 127.0.0.1:15465、`root` 空口令）测试后已 `docker rm -f`，未触碰 `pingcap/pd`|`tikv`、`doris4-*` 等他方资源。见[本轮记录](../reviews/2026-09-28-quality-restricted-projection-hardening.md)（“2026-09-28 增量：TiDB”节 + 产品矩阵）。仍待：第 10 节其余 22 产品逐库证明（PG 家族 4、MySQL 家族 DORIS/STARROCKS/GBASE8A 亦未验证）、`parseBound`/`parseTableReference` 扩产品、查询途中撤权/到期复验等按第 6 节未完成清单继续，不以家族外推；#17 整项仍未完成，六项顺序不变。

**2026-09-28 #17 最新接续：受限投影支持扩到第 4 个产品 openGauss（PostgreSQL 家族，仍只改 DB Switch 组件、无平台 SQL）。** 真实探测确认 openGauss 线协议兼容 PG、未加引号标识符小写折叠、`standard_conforming_strings` 可切换，故 `RestrictedSelectGuard.dialect()` 显式、证据门控地把 `OPENGAUSS` 映射到 Druid `postgresql` 方言；折叠分支（`resolve`/`physicalName`/去重）改用 `isLikePostgres()`（对尚未启用的同族 KINGBASE/GREENPLUM/HIGHGO/UXDB 行为中性，它们仍走 `dialect()` 默认拒绝）；未动 `quotePhysicalIdentifier` 与 `parseBound`/`parseTableReference`/`parseRelationReference` 允许名单。新增真实库差异 `RestrictedProjectionOpenGaussTest`（门控 `datahub.test.opengauss.url` + 环境变量口令、账号 `codex_test`，会话模式覆盖 `standard_conforming_strings` on/off）。验证：openGauss 真实库套件 **28/0/0/0**（差异 1 + 既有 Integer/Text/RelationIdentity），越权授权前全拒、合法查询两模式真实执行且只返回批准列；DB-less 回归 **46/0/0/0**（`RestrictedSelectGuard`/`RestrictedDmlGuard`/`RestrictedProjectionHardening`/`SqlStatementClassifier`/`SqlExecutionPlanService`，ORACLE 默认拒绝见证不变）；先 `-am install -DskipTests` 刷新 reactor 再定点跑。本机容器 `q17-opengauss-20260928`（仅绑 127.0.0.1:15464、口令仅经环境变量）复跑全绿后已 `docker rm -f`，未触碰他方容器。见[本轮记录](../reviews/2026-09-28-quality-restricted-projection-hardening.md)（“2026-09-28 增量：openGauss”节 + 产品矩阵）。仍待：第 10 节其余 22 产品逐库证明（PG 家族 4 个离线无镜像、MySQL 家族 DORIS/STARROCKS/GBASE8A 亦未验证）、`parseBound`/`parseTableReference` 扩产品、查询途中撤权/到期复验等按第 6 节未完成清单继续，不以家族外推；#17 整项仍未完成，六项顺序不变。

**2026-09-28 #17 最新接续：DB Switch 受限投影加固已完成（PG/MySQL/MariaDB 三产品，第 6 节“排查接手”执行计划 Step 2–4 已执行）。** 在组件 `RestrictedSelectGuard` 加“词法预扫 + AST 校验”两道：拒绝注释/`HINT`、MySQL 会话/系统变量与 PG `$$` 字符串（仅保留 `?`/PG `$n`）、含单引号或反斜杠的字符串字面值、查询修饰/锁/`PARTITION`/索引提示与 PG `TABLESAMPLE`，`INTO` 给明确中文错误，保留 LIMIT/OFFSET/FETCH。DB-less 加固专项 `RestrictedProjectionHardeningTest` 先红 13/19、加固后绿 19/19；整模块 DB-less 978/0/0（200 门控跳过，已含该专项与修正后的 `MysqlTableReferenceTest`）；三类真实库差异测试各会话模式 3/3 全绿（越权授权前拒绝、合法查询各模式真实执行且只返回批准列，含 PG 反斜杠注入用例）；直接消费者 Supply 48/0/0、DataForge 46/0/0（首轮 `-pl` 未 `-am` 的两处陈旧构建报错——Mockito 无法 mock `SupplyGrantReadService`、缺 `SyncBoundaryReqDTO`——经 `-am` 刷新同级构件后全绿，均非本改动）；`python tools/quality/run_all.py` 通过、无平台 SQL；同步修正早于本任务的 `MysqlTableReferenceTest` 陈旧断言。q17 三容器复用验证后已清理。见[本轮记录](../reviews/2026-09-28-quality-restricted-projection-hardening.md)。仍待：其余 22 产品（已含 openGauss、TiDB，见上条）、`parseBound`/`parseTableReference` 扩产品、查询途中撤权/到期复验与缓存旧范围释放等按第 6 节未完成清单继续，不以三产品外推；#17 整项仍未完成，六项顺序不变。

**2026-09-28 #15 最新接续：客户端所见版本子项已完成（用户选择“带了就校验”）。** 编辑、提交、撤销、补正重提、审批、要求补正可选回传 `expectedRevision`；开通/停用、收回、暂停、恢复可选回传 `expectedSourceRevision`（Long 无损，响应新增 `sourceRevision`）；提供时不一致返回 `MARKET_APPLY_REVISION_CONFLICT` 且不写入，缺省保持原服务端 CAS。前端审批页、申请列表、申请表单（编辑后以所见+1 提交）已回传。关闭校验时 28 项真实PG专项 15 项失败、开启后全部通过；完整Asset 79类3813项（真实PG 1616）0失败/跳过（2个既有过期迁移用例报错与本轮无关），前端市场 13 文件266项及 lint/格式/边界通过，见[本轮记录](../reviews/2026-09-28-sharing-client-revision.md)。无新SQL。#15 其余子项（父子续期政策、多产品绑定、schema 同名表、整体换库、完整链路）仍待；#12 之后按原顺序。

**2026-09-28 #18 最新接续：历史交付实际处置子项已完成（用户决定“撤销 + 有条件确认”）。** 新增 `POST /asset/market/apply/delivery-disposal`：对仍待核查的原操作，REVOKE 沿用普通收回链路真实撤销并冻结收回命令；CONFIRM_ORIGINAL 仅在 v2 GRANT、原回执 APPLIED 且一致、正式批准凭证 MATCHED、申请仍有效且为最新交付时把原账本确认为成功，不重发、不扩大授权，否则 `MARKET_DELIVERY_DISPOSAL_DENIED`。锁外取证、锁内复验、审计同事务、请求幂等；新增 0069 处置审计约束/索引/只可追加触发器并同步基线。后端33项真实PG专项、完整Asset 78类3785项（真实PG 1588）0失败/跳过（2个既有过期迁移用例报错与本轮无关），前端4文件129项及lint/格式/边界通过，见[本轮记录](../reviews/2026-09-28-sharing-delivery-disposal.md)。旧协议/缺批准凭证只能收回或继续待核查；Supply 孤立旧 ADMIN、批量处置、NOT_FOUND 重投政策仍待。#18/#19仍P；下一项 #15 客户端所见版本（用户选择“带了就校验”）。

**2026-09-28 #19 最新接续：提供方决定与资产归属读取后的串行化子项已完成。** 批准/驳回、补正、开通/停用、收回、暂停、恢复在申请 CAS 后经 `MarketObjectGuard.lockProviderAsset` 对资产加共享锁，按锁内当前行复验操作人仍是提供方，开通类还须仍匹配冻结绑定；权限 RPC 期间换责任人/部门、删除或换绑的旧判断不再提交，资产归属修改须等决定提交，同资产其他决定仍并行。49个真实PG反例35项先红后绿；完整Asset 77类3752项（真实PG 1555）0失败/跳过，另2个既有过期迁移用例报错与本轮无关；质量门禁通过，16:19:29临时容器/卷已清理，见[本轮实现及边界](../reviews/2026-09-28-sharing-provider-decision-serialization.md)。无新SQL/前端变化；#18/#19仍P、六项顺序不变。跨服务权限撤销窗口、令牌部门时效和申请人提交侧下架窗口仍待决定；下方历史检查点不累计测试数。

**2026-09-28 #19 最新接续：普通审计强持久性与交付意图主键子项已完成。** `MarketAuditRecorder` 改为同一真实事务、可信租户/操作人前置、恰好一行，零行抛 `MARKET_OPERATION_NOT_PERSISTED`，数据库异常原样传播并整体回滚；评价与审计同一短事务提交、治理工单提交后发起，使用日志以保存点隔离。先红探针证明旧代码审计语句失败时 PostgreSQL 提交静默回滚却返回成功并照发通知；同时修复现网 INPUT 主键模式下 `insertFrozen` 写入 NULL 主键、所有 API 交付意图失败的缺陷。专项73项及完整Asset 76类3703项（真实PG 1506）0失败/跳过，另2个既有过期迁移用例因引用已移除的0027/0030/0031报错、与本轮无关；质量门禁通过，见[本轮实现及边界](../reviews/2026-09-28-sharing-audit-durability.md)。无新SQL/前端变化，依赖基线已有的交付意图序列默认值；#18/#19仍P、六项顺序不变。下一代码目标为生命周期入口在资产归属读取后的串行化；审计覆盖范围、校核评价幂等唯一性、父子政策与真实多服务验收仍待。下方历史检查点不累计测试数。

**2026-09-28 #19 最新接续：交付内部写入完整性子项已完成。** `MarketDeliveryService` 来源INSERT/UPDATE、冻结操作INSERT、API/无API申请回填五处均要求恰好一行；冻结操作还需有效主键，失败整体回滚、不注册领取。20个真实PG反例先红后绿；最终12类862项（真实PG类350）零失败/错误/跳过、质量门禁通过，14:14:31临时容器/独占卷已清理，见[本轮实现及边界](../reviews/2026-09-28-sharing-delivery-write-integrity.md)。无新SQL/前端变化；#18/#19仍P、六项顺序不变。下一代码目标为普通审计的强持久性、失败语义与成功提交边界；资产/权限完整串行化、父子政策和真实多服务验收仍待。下方保留历史检查点，测试数不累计。

**2026-09-28 #19 最新接续：暂停/恢复授权的过程完整性子项已完成。** `pauseApply` / `resumeApply` 可信租户前置、NULL状态业务拒绝、过程必须一行，成功后才更新下游对象；失败整体回滚，保留原版本CAS、独立授权/暂停开关及SUSPEND/GRANT规则。10类640项（真实PG类213）零失败/错误/跳过、质量门禁通过，14:04:13临时容器/独占卷已清理，见[本轮实现及边界](../reviews/2026-09-28-sharing-application-suspension.md)。无新SQL/前端变化；#18/#19仍P、六项顺序不变。下一目标 `MarketDeliveryService` 来源INSERT/UPDATE、冻结操作INSERT及申请交付回填的行数完整性；普通审计强持久性、父子政策、资产/权限串行化与真实多服务验收仍待。下方历史检查点不代表当前待办，测试数不累计。

**2026-09-28 #19 最新接续：续期申请写入完整性与JSONB子项已完成。** `createRenewal` 新申请必须恰好一行且生成正数ID，RENEW过程非一行整体回滚；新Mapper显式绑定JSONB，保留原父锁、保存点、操作幂等和冻结证据，不原地延期或直接开通。8类474项（真实PG类111）零失败/错误/跳过、质量门禁通过，13:49:26临时容器/卷已清理，见[本轮实现及边界](../reviews/2026-09-28-sharing-renewal-write-integrity.md)。无新SQL/前端变化；#18/#19仍P、六项顺序不变。下一目标 `pauseApply` / `resumeApply` 的过程、对象更新时序和交付原子性；普通审计强持久性、父子政策、交付内部其余写入及真实多服务验收仍待。下方历史批次不累计。

**2026-09-28 #19 最新接续：自动到期的过程完整性与完成计数子项已完成。** `expireOne` 状态UPDATE除竞争零行外必须为1，EXPIRE过程必须为1，失败逐条回滚、不推进交付或计完成；保留短事务、数据库时钟、100条上限及批末失败报告。12类645项（真实PG类201）零失败/错误/跳过，覆盖五表回滚、双扫描、故障恢复和提交后通知；质量门禁通过，13:37:58临时容器/卷已清理，见[本轮实现及边界](../reviews/2026-09-28-sharing-application-expiration.md)。无新SQL/前端变化，#18/#19仍P、六项顺序不变。下一明确目标 `createRenewal` 续期INSERT/主键及RENEW过程完整性，父子级联政策不擅自调整；暂停/恢复、交付内部写入及真实多服务验收仍待。下方历史批次不累计。

**2026-09-28 #19 最新接续：收回授权的过程完整性子项已完成。** `revokeApply` 可信租户前置，过程非一行抛冲突并回滚，不继续来源/终态REVOKE或成功通知；保留原CAS、提供方权限、到期/历史快照兼容及原批准凭证。10类592项（真实PG类161）零失败/错误/跳过，覆盖真实五表回滚、提交失败、并发原因一致、来源溢出及无API路径；质量门禁通过，13:27:02临时容器/卷已清理，见[本轮实现及边界](../reviews/2026-09-28-sharing-application-revocation.md)。无新SQL/前端变化；#18/#19仍P、六项顺序不变。下一明确目标 `expireOne`/`expireDueApplies` 到期失效的过程行数、回滚及批内失败处理；交付内部其余写入行数、资产/权限串行化及真实多服务验收仍单独待办。下方历史批次不累计。

**2026-09-28 #19 最新接续：开通/停用授权的过程完整性子项已完成。** `authApply` 可信租户前置、NULL状态业务拒绝、过程非一行回滚，成功后才更新供下游使用的授权值；原幂等、到期、冻结批准范围及SUSPEND/GRANT双开关规则保留。10类609项（真实PG类162）零失败/错误/跳过，覆盖来源/交付/COMMIT故障原子回滚、同目标竞争、原凭证不变、来源溢出和无API路径；质量门禁通过，13:19:11临时容器/卷已清理，见[本轮实现及边界](../reviews/2026-09-28-sharing-application-authorization.md)。无新SQL/前端变化；#18/#19仍P、六项顺序不变。下一明确目标 `revokeApply` 收回授权的过程完整性与终态交付回滚；交付内部其余零行写入、资产/权限串行化及真实多服务验收仍单独待办。下方历史批次不累计。

**2026-09-28 #19 最新接续：申请人撤销的过程完整性子项已完成。** `cancelApply` 可信租户前置、NULL状态业务拒绝、过程非一行整体回滚；保留草稿/待审资格、申请人限制、原版本CAS及无交付/通知策略。8类539项（真实PG类145）零失败/错误/跳过，覆盖真实COMMIT失败、双撤销和对提交/审批四种胜负顺序；质量门禁通过，12:43:53临时容器/卷已清理，见[本轮实现与验证边界](../reviews/2026-09-28-sharing-application-cancellation.md)。无新SQL/前端变化；#18/#19仍P、六项顺序不变。下一明确目标为 `authApply` 开通/停用授权的过程完整性及来源版本、交付/通知失败边界；其他生命周期、交付写入及真实跨服务验收仍逐项待办。下方历史批次不累计。

**2026-09-28 #19 最新接续：审批通过/驳回的过程完整性及旧意见通知子项已完成。** `checkApply` 可信租户前置、过程必须一行，成功后同步本轮审核人/时间/意见，过程失败不进入交付或成功通知；原冻结范围/版本CAS和提交后机制保留。真实PG覆盖正式批准凭证、来源、交付及COMMIT失败整体回滚，通知不再读旧意见；同步旧冻结范围夹具所缺前置。10类753项（真实PG类133）零失败/错误/跳过，质量门禁通过，12:33:55临时容器/卷已清理，见[本轮实现及验证边界](../reviews/2026-09-28-sharing-application-checking.md)。无新SQL/前端变化，原升级前置不变；#18/#19仍P、六项顺序不变。下一明确目标 `cancelApply` 的过程完整性/回滚，其他交付写入行数、资产/权限串行化与真实跨服务验收仍单独待办。下方历史批次不累计。

**2026-09-28 #19 最新接续：要求补正的过程完整性子项已完成。** `requestSupplement` 增加可信租户前置和过程恰好一行门禁，失败回滚状态/说明；原审核权限+提供方Guard、版本CAS、说明可空和不改冻结快照/时限规则保留。7类505项（真实PG类113）零失败/错误/跳过，质量门禁通过，12:22:33临时容器/卷已清理，见[实现及验证边界](../reviews/2026-09-28-sharing-supplement-request.md)。无新SQL/前端变更，原升级前置不变；#18/#19仍P、六项顺序不变。下一明确目标是 `checkApply` 通过/驳回的过程写入与失败回滚、通知/交付边界，撤销/续期等继续逐项核查。下方为历史检查点，不累计测试数。

**2026-09-28 #19 最新接续：补正后重新提交的JSONB/过程完整性子项已完成。** `resubmitApply` 入口校验可信租户，JSONB显式参数化，过程非一行回滚；保留旧revision做CAS、新revision冻结快照及原时限规则，NULL/上限和并发重提已验证。最终9类802项（真实PG类346）零失败/错误/跳过，质量门禁通过，12:14:11临时容器/独占卷已清理，见[实现与验证边界](../reviews/2026-09-28-sharing-supplement-resubmission.md)。无新平台SQL/前端变化，原升级前置不变；#18/#19仍P、六项顺序不变。下一明确代码目标是 `requestSupplement` 的过程写入与失败回滚，审批/撤销/续期等仍逐项核查；不把本子项等同整项完成或真实多服务验收。下方历史检查点与测试数不累计。

**2026-09-28 #19 最新接续：独立草稿提交的过程完整性子项已完成。** `submitApply` 入口校验可信租户，沿用原状态/归属/资产/版本CAS，快照使用绑定参数显式转JSONB；过程非一行抛业务冲突，整笔状态/快照/时限回滚，不发成功通知。最终7类696项（真实PG类317）零失败/错误/跳过，质量门禁通过，临时容器/卷已清理，见[本轮实现与验证边界](../reviews/2026-09-28-sharing-draft-submission.md)。无新平台SQL/前端变更，原升级前置不变；#18/#19仍P、剩六项顺序不变。下一明确目标是补正后重新提交 `resubmitApply` 的JSONB更新及过程行数，其他审批/撤销/续期仍逐项核查，不把本轮结果外推为整个生命周期或部署已验收。下方历史测试数不累计。

**2026-09-28 #19 最新接续：普通申请创建的定向冲突与完整写入子项已完成。** 普通创建与需求草稿共用初始INSERT/行数/生成主键门禁；仅识别0050在途冲突，其他约束透传。直接提交先冻结快照再一次写入，过程非一行整体回滚；可信租户前置、客户端ID清除、JSONB显式绑定及提交后通知已验证。5个原路径反例与1个JSONB探针先红后绿，最终15类1677项（真实PG类628）零失败/错误/跳过，质量门禁通过，临时容器/卷已清理，见[本轮实现及验证边界](../reviews/2026-09-28-sharing-application-creation.md)。无新平台SQL/前端变更，原0050/0068前置不变；#18/#19仍P、剩六项顺序不变。下一核查独立提交及其他生命周期的过程写入失败，不把本轮初始创建外推为全部审批/续期或部署已验收；下方历史测试数不累计。

**2026-09-28 #19 最新接续：需求确认中新建草稿的并发冲突与写入完整性子项已完成。** 复用0050部分唯一索引定向处理在途竞争，零行插入复读区分重复与其他写入失败；草稿必须恰好一行且回填正数主键，过程必须恰好一行，否则外层确认/需求一起回滚。其他约束异常透传，不隐式改选或重投，沿用租户和审计组件。3个真实PG先红反例转绿，最终14类1557项（真实PG类598）零失败/错误/跳过、质量门禁通过，临时容器/卷已清理，见[本轮实现与验证边界](../reviews/2026-09-28-sharing-demand-draft-creation.md)。无新平台SQL/前端变化，原0050/0068前置不变；#18/#19仍P，剩六项顺序不变。下一代码核查为普通申请创建的宽泛唯一异常转换与写入行数，其他流程的过程记录行数尚未统一收口。下方检查点保留历史，测试数不累计。

**2026-09-28 #19 最新接续：已有申请复用的并发保护子项已完成。** 候选只读一次，再复用所属申请行锁，重验当前用户/资产/在途状态并持锁至外层确认提交；候选失效返回业务冲突，不空指针、不换候选、不降级创建。锁内仍合法的当前记录可复用，不改写跨需求申请或改变授权政策。15个先红反例转绿，14类1502项（真实PG类574）零失败/错误/跳过，质量门禁通过，临时容器/卷已清理，见[实现及验证边界](../reviews/2026-09-28-sharing-demand-apply-reuse.md)。本轮无新平台SQL/前端变化，原0050/0068前置不变；#18/#19仍P，六项顺序不变。下一代码核查为无候选时首次创建的唯一冲突转换及草稿/过程INSERT行数；不把本轮复用保护外推为全部创建幂等。下方检查点为历史，测试数不累计。

**2026-09-28 #19 最新接续：接受匹配/生成申请期间资产一致性子项已完成。** 两种接受操作先验资产，再在匹配确认及申请创建/复用后共用资产共享锁、23字段复验和需求CAS；下架、删除、换租户或依据变化时回滚确认/草稿/过程，不发成功通知。拒绝失效匹配保留，异常空关联精确比较且禁止接受/并发覆盖，不放宽正式NOT NULL约束。最终14类1403项（真实PG类542）零失败/错误/跳过、质量门禁通过，临时容器/卷已清理，见[本轮实现与验证边界](../reviews/2026-09-28-sharing-confirmation-asset-consistency.md)。无新平台SQL/前端变化，0068部署前置不变；#18/#19整项仍P、剩六项顺序不变。下一代码核查为已有申请复用期间申请本身的状态、归属、删除或绑定变化，不能把资产锁当作申请版本保护。下方旧检查点保留历史，测试数不累计。

**2026-09-28 #19 最新接续：新增匹配期间资产变化的一致性子项已完成。** 插入匹配后、需求CAS前按租户锁内重读资产并比较23项绑定/发布/供给资格字段；下架、删除、换租户或依据变化回滚，不通知。共享锁保护至提交且允许不同需求正常并行，显示名称取新值，保留原发布准入与可空历史字段。30个先红反例转绿，14类1210项（真实PG类443）零失败/错误/跳过，质量门禁通过，见[专项记录](../reviews/2026-09-28-sharing-match-asset-consistency.md)。本轮无新平台SQL/前端变更，0068部署前置不变；#18/#19整项仍P、剩六项顺序不变。下一代码核查为后续确认匹配/生成申请期间的资产竞争，不能从新增匹配保护外推其已修复。

**2026-09-28 #19 最新接续：同需求同资产并发匹配唯一性子项已完成。** 平台按租户/需求/资产建立未删除记录唯一索引，原子插入只忽略该业务冲突；竞争失败返回重复提示，不推进需求或通知，其他数据库异常透传。4 个先红反例转绿，14 类 1024 项（真实 PG 类 400）零失败/错误/跳过，质量门禁通过，见[实现与部署顺序](../reviews/2026-09-28-sharing-demand-match-uniqueness.md)。新增 **0068**，历史重复/错误索引/可空身份列会安全阻断升级，不自动清理历史；已有库先迁移再发布代码。完整新库基线仍截断，不可据片段测试用于安装。#18/#19 整项仍 P、剩六项顺序不变；下一代码核查为新增匹配期间资产变化的一致性。

**2026-09-28 #19 最新接续：响应/新增匹配与需求终态竞争子项已完成。** 子记录 INSERT 和需求 CAS 都必须恰好一行，在同一事务复验原状态、归属、验收依据和标题/用途/字段；冲突回滚，不留孤立响应/匹配、不发成功通知。开放的 MATCHING/MATCHED/APPLYING 保持原进度，真实 COMMIT 失败也不发通知。34 个先红反例转绿，13 类 980 项（真实 PG 类 360）零失败/错误/跳过、质量门禁通过，见[本轮记录](../reviews/2026-09-28-sharing-demand-contribution-atomicity.md)。临时库/卷已清理，无新平台 SQL/前端变化；#18/#19 整项仍 P，剩六项顺序不变，不代表同资产并发去重或真实部署已验收。

**2026-09-28 #19 最新接续：需求编辑/删除/发布/关闭的状态竞争保护子项已完成。** 编辑不再回写旧状态和归属，四类写入比较原状态、归属及验收依据，必须恰好命中一行；已发布/转交/验收/删除后旧请求拒绝，保留原权限与逻辑删除，独立发布使用数据库时间。53 个先红反例转绿，11 类 813 项（真实 PG 类 292）零失败/错误/跳过，质量门禁通过，详见[本轮记录](../reviews/2026-09-28-sharing-demand-mutation-concurrency.md)。临时库/卷已清理，无新增平台 SQL/前端变化；下一代码目标为响应/新增匹配与终态竞争，#18/#19 整项仍 P，剩六项顺序不变。

**2026-09-28 #19 最新接续：匹配确认、需求状态与申请草稿原子性子项已完成。** 接受并建申请、只接受、拒绝三种操作均复验原需求和匹配关联；首次更新、申请回填和需求 CAS 必须恰好命中一行，冲突回滚匹配/草稿/过程，不发成功通知。保留原申请复用与 APPLYING 语义，使用数据库确认时间。39 个先红反例转绿，最终 9 类 522 项（真实 PG 类 185）零失败/错误/跳过，质量门禁通过，见[本轮记录](../reviews/2026-09-28-sharing-match-confirmation-atomicity.md)。临时库/卷已清理，无新增平台 SQL/前端变化；#18/#19 整项仍 P、剩六项顺序不变，不代替历史处置或真实部署验收。

**2026-09-28 #19 最新接续：需求验收的匹配关联保护子项已完成。** 只接受当前需求中已接受且申请/资产/用户一致的匹配；交付检查后锁匹配、复验关键关联，再复用申请锁/24 字段比较、需求 CAS 和末次数据库时间，防止换绑/删除后仍验收成功。保留同用户同资产跨需求复用和历史验收重试语义。19 个先红反例转绿，最终 8 类 405 项（真实 PG 类 120）零失败/错误/跳过、质量门禁通过，详见[本轮记录](../reviews/2026-09-28-sharing-demand-match-acceptance.md)。无新平台 SQL/前端变化；#18/#19 整项仍 P、六项顺序不变，不批量改写历史验收或替代真实部署验收。

**2026-09-28 #19 最新接续：交付详情与人工对账回显授权一致性子项已完成。** 共用已有双快照授权读取，权限 RPC 期间转交、删除、绑定/版本或身份变化拒绝旧数据；保持全部详情字段和原回执不重发语义。对账提交后回显失效不撤销已提交审计，须重新读取当前账本。15 个先红反例转绿，最终 11 类 484 项（真实 PG 类 231）零失败/错误/跳过，质量门禁通过；实现、资源清理与验证边界见[本轮记录](../reviews/2026-09-28-sharing-delivery-detail-authorization.md)。无新平台 SQL/前端变化；#18/#19 整项仍 P、六项顺序不变，不代替完整历史处置或真实多服务验收。

**2026-09-28 #19 最新接续：人工原回执对账写回的授权竞争子项已完成。** 查询前后复用对象授权快照、固定入口身份，短事务锁定申请/资产/操作后再次复验；转交、删除、绑定/版本/身份变化不再写入旧结果，重复请求只提交一份账本与审计，审计失败回滚。9 类 410 项（真实 PG 类 195）零失败/错误/跳过；资源收尾、重复竞争验证及限制见[本轮记录](../reviews/2026-09-28-sharing-manual-recheck-authorization.md)。无新 SQL/前端变化，NOT_FOUND 仍不重发；#18/#19 整项仍 P、六项顺序不变，不替代历史原批准处置或真实多服务验收。

**2026-09-28 #19 最新接续：到期撤销与交付补偿的批内故障隔离子项已完成。** 单条事务/领取/回写失败不再中断本批后续条目，到期阶段失败不再阻断已有交付重试；批末仍报告失败，不吞错冒充成功。保留原冻结身份、租约及先查回执恢复，竞争丢弃不计成功。9 类 276 项（真实 PG 类 89）零失败/错误/跳过，租户切面失败映射、质量门禁及差异检查通过；临时 PG/卷已清理。验证与资源收尾见[本轮记录](../reviews/2026-09-28-sharing-maintenance-failure-isolation.md)，无新增 SQL/前端改动；#18/#19 整项仍 P、六项顺序不变，不据此开放人工重投或宣称完整历史处置完成。

**2026-09-28 #18 最新接续：历史清点合法大范围误判与异常日志保护子项已完成。** 64 KiB 分页投影不再把正式协议允许的宽字段范围判为损坏；超内联范围按条、限 4 MiB 在同一只读快照读取，不累积整页载荷，读库失败不伪装成证据无效。解析失败不再记录冻结范围/回执正文，坏范围与 JSON null 墓碑语义保持区分。最终 5 类 115 项（真实 PG 类 72）零失败/错误/跳过，质量门禁通过，新增 35 项含于总数。本轮仅 Supply，无前端/线协议/升级 SQL 变化；证据及资源收尾见[本轮记录](../reviews/2026-09-28-sharing-review-large-scope.md)。#18/#19 仍 P，历史原批准与实际处置/重投政策、部署验收仍待，六项顺序不变。

**2026-09-28 #18/#19 最新接续：历史核查权限竞争子项已完成。** 权限 RPC 前后独立短快照复验原操作/对象/批准凭证，取证、历史查询和已保存请求重试绑定同一租户/用户/部门；变化拒绝旧结果，不隐式重试、不修改授权。30 项反例先红后绿，最终 6 类 272 项（真实 PG 类 150）通过，零失败/错误/跳过；新增 32 项包含在总数内，质量门禁通过。本轮仅 Asset，无新增 SQL/前端变更，完整证据及资源收尾见[本轮记录](../reviews/2026-09-28-sharing-review-authorization-race.md)。#18/#19 整项仍 P：历史原批准清点、真正处置、人工重投政策和部署验收仍未完成；剩六项顺序不变。

**2026-09-28 #19 最新接续：暂停/终态撤销呈现子项已完成。** 冻结动作经过原身份/摘要校验后区分 SUSPEND/REVOKE，缺证据保持 UNKNOWN；技术 opType 不变，新增只读 action 字段，暂停确认使用 SUSPENDED 且不可用。后端 10 类 350 项（真实 PG 56 项）、前端 12 文件 404 项通过，零失败/跳过；质量门禁及定点 lint/格式/边界通过。类型检查仍 61 条范围外旧债，本轮文件 0；临时库/卷已清理，无新 SQL。页面分开显示请求意图、技术类型和结果，证据及部署边界见[本轮记录](../reviews/2026-09-28-sharing-suspend-revoke-presentation.md)。下一步为历史原批准清点/实际处置边界与多服务验收；#18/#19 仍 P、剩六项顺序不变。下方“暂停/撤销待呈现”属于历史状态，不重复累计。

**2026-09-28 #19 最新接续：运行配置与当前来源授权绑定子项已完成。** 已复现并修复旧发布配置搭配新批准快照仍被执行的问题；执行前核对身份、行版本、合同重算、业务版本及绑定，异常不回退旧授权/缓存、不自动重试。11 类 287 项（真实 PG 4 类 207 项）零失败/错误/跳过，质量门禁通过；实际投影取数验证撤销后字段收窄、查询中途变化/到期后丢弃结果。范围仅 Supply，无新增平台 SQL 或前端修改，临时容器/卷已清理。最终验证和限制见[本轮记录](../reviews/2026-09-28-sharing-runtime-grant-binding.md)。下一代码目标为暂停/终态撤销的动作呈现；#18/#19 整项仍 P、剩六项顺序不变。下方“当前聚合/撤销/到期验证”的历史待办以本轮证据为准，不累计历史测试数。

**2026-09-28 #19 最新交付：技术交付统计口径、成对计数和统计页异步一致性子项已完成。** 真实 PG 复现并修复两次读取拼出 2/1 的问题，分子/分母现为同一 SQL 快照；保留全租户技术口径，明确不随目录版本筛选、不代表当前访问。统计页补身份/版本 latest-wins、独立图表实例和缺数安全提示，后端异常不再外泄。后端 14 类 421 项（真实 PG 类 143）、前端 4 文件 119 项零失败/跳过，门禁及定点 lint/格式通过；全应用类型仍 61 条范围外旧债、本轮文件 0。无新增 SQL，临时 PG/卷清理。#18/#19 整项仍 P、剩六项；下一步当前来源聚合与多来源撤销/到期/合同变更后的实际访问，见[本轮实现与边界](../reviews/2026-09-28-sharing-delivery-statistics.md)。下方统计待办和测试数为历史，不重复累计。

**2026-09-28 #19 最新交付：当前申请与交付确认的版本/绑定/范围一致性子项已完成。** 已确认账本必须匹配当前批准及来源版本、应用/API、来源身份和规范冻结范围，已有正式批准同步核对；新版缺账本或清空 API 不能沿用旧成功。详情与资源/需求共用判断，两处 Mapper 旧行/空值缓存已修。31 类 655 项（真实 PG 类 327）零失败/错误/跳过，质量门禁通过；本轮新增 104 项包含在总数中。无本轮升级 SQL/前端修改，临时库/卷已清理。#18/#19 整项仍 P、剩六项；下一步核对技术统计与当前可访问口径，再推进当前聚合/多来源撤销访问验证。完整实现、兼容和未验边界见[本轮交付](../reviews/2026-09-28-sharing-current-delivery-binding.md)。下方版本/绑定待办是历史时点，不重复计入。

**2026-09-28 #19 最新交付：验收与原申请并发变更子项已完成。** 交付检查后复用所属申请行锁，重读并核对 24 个授权关键字段，持锁至需求 CAS/期限复验事务结束；撤销、改期限/绑定/范围、删除或归属变化不再使用旧 DO 验收，无关使用记录不误报冲突。补锁缓存/保存点回归。14 类 356 项（真实 PG 类 148）及门禁通过，零失败/错误/跳过；需求实库专项修复后连续三次通过。无本轮升级 SQL/前端改动，单个临时库/卷已清理，未动业务库。#18/#19 整项仍 P、剩六项；下一明确代码目标是交付确认与当前申请版本/绑定的完整关联，见[本轮实现、反例和边界](../reviews/2026-09-28-sharing-demand-grant-concurrency.md)。下方原申请并发待办为历史状态，不累计测试数。

**2026-09-28 #19 最新交付：申请/需求数据库时钟与到期边界子项已完成。** 复用实际访问授权规则，起点包含、到期点排除；禁止 MyBatis 缓存旧数据库时间。需求交付后及验收 CAS 写后复验，到期/时钟空值/异常回滚，验收时间由数据库生成。18 类 333 项（真实 PG 类 125）通过，零失败/错误/跳过，质量门禁通过；无新增迁移或前端改动，00:32:14 前单个临时 PG/卷已清理，业务库未动。#18/#19 整项仍 P、剩六项；下一明确代码目标为验收期间原申请撤销/期限/绑定变化的版本复核，详见[实现、跑红证据与剩余边界](../reviews/2026-09-28-sharing-database-clock.md)。下方“时钟待修”均为历史状态，不累计历史测试数。

**2026-09-28 #19 最新交付：本地交付结果统一、资源/总览异步状态子项已完成。** 申请不再把 SUCCESS+UNCONFIRMED 或 CANCELLED 显示成已生效/无需交付，技术状态读取同一账本；资源 authorized/usable 共用一份有效申请。两页身份切换/失败清空/迟到响应已修，公开目录与预览检查分离，统计缺失显示未知、原计数定义不变。后端 6 类 137 项、前端 11 文件 177 项全部通过，无失败/跳过；门禁、定点 lint/格式/边界通过。类型检查仍 60 条范围外业务错误+1 条共享 Monaco，本轮文件 0。无新 SQL、测试库或业务库操作。#18/#19 整项仍 P、剩六项；下批优先核对申请/需求的数据库时钟及到期边界，详见[实现、反例与余项](../reviews/2026-09-28-sharing-delivery-presentation.md)。下方历史批次不累加。

**2026-09-27 #18/#19 最新交付：旧 ADMIN 隔离代码与本地回归子项已收口，应用详情异步状态修复完成。** 旧授权返回前复验撤销/禁用/到期与控制版本，合同口径复用网关既有规则；详情、列表、范围及选项按身份隔离，失败清除旧有效状态，确认后复验本行版本，未知提交不直接重发，来源表改用系统＋来源键复合身份。后端 11 类 311 项（真实 PG 类 253）、前端 10 文件 283 项通过，零失败/跳过；质量门禁通过，类型检查仍 61 条范围外错误、本次文件 0。无新 SQL；23:47:11 前本轮临时 PG/卷清理，未动业务库。#18/#19 整项仍 P，六项顺序不变，见[本轮实现、反例和验证](../reviews/2026-09-27-sharing-legacy-and-detail-consistency.md)。下方批次保留历史，不累加数字。

**2026-09-27 #18/#19 最新接续：Supply 应用页历史只读核查入口、有界分页与管理端返回前状态复验已完成。** 三类记录独立捕获/回传 `throughId`，不把分页末尾或本页计数当作完整历史快照；读取期间撤销、禁用、到期或版本改变不再返回旧 v2 ACTIVE。后端 9 类 249 项（真实 PG 类 219）、前端 9 文件 115 项通过，无失败/跳过；质量门禁通过。全应用类型检查仍 61 条范围外错误，本次写集无错误。本轮无新 SQL，未执行真实历史处置。#18/#19 仍 P，剩六项顺序不变，见[实现、验证与具体余项](../reviews/2026-09-27-sharing-review-scan-and-current-state.md)。下方各批为历史，不累加测试数。

**2026-09-27 #18/#19 最新并行交付：正式批准凭证已接入只读核查、持久核查审计及页面；Supply 授权列表与来源贡献状态同步权威读取。** 核查明确区分 MATCHED/MISSING/CONFLICT/UNVERIFIABLE/NOT_APPLICABLE，APPLIED 不等于原批准匹配，历史不回填，核查不改变授权。Supply 不再按旧兼容位判断 v2 可用，阻断/待核查来源不显示当前贡献。后端 28 类 563 项（真实 PG 类 431）、前端 110 项通过，零失败/跳过；全应用类型仍 61 条范围外错误、中台 0。本批无新 SQL，已有库仍需上一批 0066→0067 前置；#18/#19 保持 P，剩六项顺序不变。见[最新交付、复跑命令与未完成边界](../reviews/2026-09-27-sharing-approval-review-and-status.md)。下方各批次数为历史，不累加。

**2026-09-27 #18/#19 最新交付：新批准凭证与恢复范围核对已落地。** 新批准的 API 申请在审批/来源/交付同一事务追加正式凭证，记录原操作者与规范批准摘要；恢复必须匹配原主体、字段、期限、用途、合同和绑定，不允许换批准版本或清空 API 绕过。凭证禁止普通 DML 修改/软删除/物理删除，存在凭证时禁止清空审计表；其他审计事件仍可正常维护。新增 `0067`，扩大回归 19 类 347 项（真实 PG 类 242）零失败/错误/跳过。本轮未修改前端或 HTTP/RPC 契约，不重用上轮前端数字冒充新验证。旧申请不补造批准，旧节点不能保证该新保护；完整历史清点/原批准核验和实际迁移仍待，#18/#19 继续 P、剩六项不变。见[实现、升级边界与验证](../reviews/2026-09-27-sharing-approval-evidence.md)。

**2026-09-27 #18/#19 最新交付：核查结论持久审计子项已完成。**新增“继续待核查 / 需撤销后重申请”结论与分页历史；服务端重新取证、锁内复验，原审计表追加证据摘要和操作者，同租户请求编号跨节点幂等，回执丢失可原请求重试。两种结论均只是待办记录，不实际撤销、重新批准或恢复授权。新增迁移 `0066`；后端最新报告去重 14 类 246 项（真实 PG 类 160）、前端 7 文件 66 项通过，无失败/错误/跳过。前端 lint 零错误、15 条测试替身警告；类型检查仍 61 条范围外错误、中台 0。详见[实现、SQL 顺序与验证边界](../reviews/2026-09-27-sharing-review-decisions.md)。#18/#19 仍 P，剩六项不变；下一步完整历史/不可变批准证据与真正处置。下方“核查决定/审计尚未开发”均为历史时点，本批已完成有限待办记录，不等同所有处置。

**2026-09-27 #18/#19 最新接续：**Asset 原冻结命令 ↔ Supply 原回执的单次只读核对及页面入口已完成，覆盖身份/摘要/版本匹配、查询期间本地变更/撤权丢弃、旧协议/缺证据分类；不批准、不迁移、不重发。后端 13 类 222 项（真实 PG 类 136）、前端 44 项通过；质量门禁/边界/lint/格式通过。全应用类型仍 61 条范围外诊断，中台 0。20:20:16 前临时 PG/卷已清理，无本批 SQL。见[最新交付与验证边界](../reviews/2026-09-27-sharing-original-evidence.md)。下一步核查决定、持久审计与历史处置；两项仍 P、剩六项不变。下段 365/31 为上一批记录，不与本次累加。

**2026-09-27 #18/#19 本批交付：**Supply 历史授权/来源/操作只读分类已落地；#19 需求验收终态/归属 CAS、原交付身份及“服务审批 → 交付核查”页面已接入，三处申请视图使用实际 usable 并补 REVOKED/SUPERSEDED。后端 21 类 365 项（真实 PG 类 248）、前端 31 项通过，零失败/错误/跳过；门禁、定点 lint/格式通过，全应用类型仍 61 条范围外诊断、中台 0。临时 PG/卷已清理，无本批 SQL。完整范围与余项见[专项记录](../reviews/2026-09-27-sharing-review-and-demand-cas.md)。#18/#19 仍 P，当前六项不变。

2026-09-27 最新用户决定：**第 #33 项标记为已完成（用户确认结项）**，不是将原 33 项全部标记完成；其未运行的真实部署验收保留为上线事项，不重新计入开发待办。当前剩余 **6 项：#18、#19、#12、#15、#17、#11**，优先顺序调整为 **#18 → #19 → #12 → #15 → #17 → #11**。下文七项及优先 #12 的表述是历史记录，以本段为准。

2026-09-27 历史用户决策：**#7 标记为已完成（用户确认结项）**；生产规模参数、正式告警接收方及部署故障验证转为上线验收事项。彼时剩余七项并优先 #12；现按顶部六项及新顺序推进。下方各历史阶段的“八项不变”不作为当前统计。

整理日期：2026-09-25；最新更新：2026-10-01，#12 复测启动原子性与确认执行证据复验。保留原 33 项编号，面向任何后续开发者或开发工具，不绑定 Grok/Codex。

**第二十四阶段新增：**PG16 INSERT/COPY 回执、完整批次摘要和实际质量读取已绑定普通持久表的物理身份；同名重建、TRUNCATE、VACUUM FULL 不再仅留下“旧事务可见”而没有对象匹配状态，缺身份明确 UNPROVEN。样本外对象变化取消完整摘要，提交后变化不发回执、不重投；DataForge JSONB/API 与质量审计已贯通。28 类 548 项（真实 PG 229）零失败/错误/跳过，门禁通过，19:12:30 前临时库/卷清理，无新 SQL/前端/POM 或业务库操作。见[第二十四阶段](../reviews/2026-09-27-quality-relation-commit-binding.md)。恢复/克隆代际、字段值范围、持久准入及真实副本仍待，#12 保持 P、剩余七项；下方“原生对象缺失”是历史时点，当前 PG16 已补有限对象绑定，不外推其他产品。

**第二十三阶段新增：**PG16 COPY 原生提交回执已补齐，复用原 COPY 编码/行数核对及共用事务边界，确认退出协议后复验、提交/状态/清理全部成功后才返回；异常不发回执、不重投。同步/异步 18 批、DataForge JSONB/API 与质量审计均已覆盖 INSERT/COPY 两模式。27 类 509 项（真实 PG 200）零失败/错误/跳过，门禁通过；17:55:50 前临时库/卷清理，无新 SQL、前端/POM 或业务库操作。见[第二十三阶段](../reviews/2026-09-27-quality-copy-commit-receipt.md)。原生对象/恢复代际、字段值范围、持久准入及真实副本仍待，#12 保持 P、剩余七项。以下“COPY 无见证”仅为历史时点，不再覆盖当前 PG16 路径，也不外推到兼容产品。

**第二十二阶段新增：**超过 16 批的完整批次摘要及同快照范围诊断已落地。PG16 INSERT 全部确认批次以常量空间摘要留存，核对整个事务区间，不能仅以首尾可见忽略中间在途事务；DataForge JSONB/API、Governance 实际规则审计已接通，观察变化或清理失败丢弃。20 类 378 项（真实 PG 144）零失败/错误/跳过，质量门禁通过，16:00:38 前测试库/卷已清理；无新 SQL、前端/POM 或业务库操作。见[第二十二阶段](../reviews/2026-09-27-quality-commit-range.md)。原生对象/恢复代际、字段值范围、持久准入及跨路由副本仍待，#12 仍 P，剩余七项。下方旧“16 批上限”等保留历史，不再视为新摘要路径的批次数上限。

**第二十一阶段新增：**PG16 指定顶层提交在实际质量读取快照的可见性核对已接通，最多 16 份、不接受截断/错序/缺失清单；区分不可见/未提交/路由不符，保存点隔离查询失败，清理失败不发布，批次前后变化丢弃观察。18 类 336 项（真实 PG 137）零失败/错误/跳过；临时库/卷于 15:44:40 前清理，无新 SQL、前端/POM 或业务库操作。见[第二十一阶段](../reviews/2026-09-27-quality-commit-visibility.md)。原生对象/恢复代际、完整范围和持久准入判定仍待，副本不自动 PASS；#12 仍 P，剩余七项。

**第二十阶段历史交付：**PostgreSQL 16 INSERT 实际批次提交见证已接通：同连接采集/复核顶层 xid，提交状态与清理成功后交付；异常继续 UNKNOWN、不重投。逐表有界留存 16 份、缺失/截断不冒充完整，DataForge JSONB/API 与 Governance 事实接线通过，副本仍不自动 PASS。最终 24 类 416 项（组件 168、消费方 248，真实 PG 130）零失败/错误/跳过，质量门禁通过。无新 SQL/前端/POM 变更，15:17:32 临时 PG/测试卷已清理，未动业务库。见[第二十阶段及验证边界](../reviews/2026-09-27-quality-commit-receipt.md)。当时原生关系/恢复代际、完整范围、同次读取可见性与其他写入模式仍待，#12 为 P、八项不变；当前以后续记录为准。

第十九阶段历史交付：PostgreSQL 同规则基础统计、机构分组与问题主键跨快照混用已修。DB Switch 新增只读一致快照/保存点，Governance 在同一尝试内读取、复验和清理后才保存结果；保留原时间契约，机构查询失败不破坏有效基础统计。原 expected 2 / actual 3 的实库反例转绿；23 类 351 项（组件 105、消费方 246，真实 PG 94）零失败/错误/跳过，门禁通过。无新 SQL/前端变更，未动业务库，14:30:45 前单个 PG/测试卷已清理。见[第十九阶段与验证边界](../reviews/2026-09-27-quality-read-snapshot.md)。快照不是同步到数证明，副本仍 WAITING_DATA；其他产品快照、原生提交/恢复代际与结果对账继续待办，#12 仍部分完成、八项不变。

第十八阶段历史交付：UXDB COPY 异常后隐式 INSERT 重投已移除，复用共用独立事务/未知结果边界；拒绝提交调用方已有事务、核对精确回执行数，活动 COPY 异常淘汰连接而不阻塞 rollback，并修复标识符转义。原四项反例及新增引用反例转绿；组件 155 项、直接消费方 196 项，共 26 类 351 项（真实 PG 46）通过，零失败/错误/跳过。**PG 协议模型不等于 UXDB 原生验收**，后者仍待介质/驱动/许可或隔离实例。无新 SQL/前端变更，未动业务库；14:01:05 前自有 PG/测试卷已清理，门禁通过。见[第十八阶段与完整验证边界](../reviews/2026-09-27-quality-uxdb-copy-outcome.md)。下一项为结果对账/同次读取到数证明，#12 仍部分完成，八项总数不变。

第十七阶段历史交付：主数据派发按运行编号独立停止已实现，未发布的手动运行可停止，不改变发布或周期排期；WAITING→4、发送后→UNKNOWN，操作者/时间/原因与告警原子落库，同请求重试不会误停新运行。前端先读最新记录、填写原因并展示审计，权限/大 ID/旧响应隔离同步。新增 0065，当时后端 12 类 214 项（真实 PG 94）及前端 45 项通过，零失败/错误/跳过；未动业务库。详见[第十七阶段、SQL 与验证边界](../reviews/2026-09-27-quality-master-run-stop.md)。不累计历史测试数字。

第十六阶段历史交付：主数据 MQ 确认与失败资源收尾已修：Kafka all/幂等 SDK 重试、有界等待/有效 offset；Rabbit mandatory/持久消息/return+confirm、禁用自动恢复；所有出口终止本次会话、保存/执行共用纯预检和部分确认计数。原四反例转绿，分服务串行验证的 13 类最新报告共 204 项（真实 PG 81、Rabbit 6、Kafka 2），零失败/错误/跳过，重复执行不累加。无新 SQL、前端/框架/POM 变更，未动业务库。见[第十六阶段、兼容影响与实测边界](../reviews/2026-09-27-quality-master-mq-confirmation.md)。下一项为未发布手动运行的独立停止入口，随后结果对账/到数证明；#12 继续部分完成，八项总数不变。

第十七阶段补充验证：质量门禁、前端边界/定点 lint/格式检查及双仓 diff 检查通过；全应用 typecheck 仍 61 条范围外诊断、中台 0，不称为全应用通过。单个临时 PostgreSQL 和一次性卷已清理，未升级业务库。具体测试命令、SQL/权限及剩余边界见本批专项记录。

第十五阶段历史交付：主数据 HTTP 隐式重投修复，单次 body、禁止重定向、有界响应/纯预检、部分确认计数及取消收尾。当时 10 类 156 项（真实 PG 75）通过，另开 SDK 全方法重试的 37 项通过，不与本批累计。见[第十五阶段](../reviews/2026-09-27-quality-master-http-delivery.md)。

第十四阶段历史交付：Governance 关键终态持久告警、历史补账、冻结接收者、租约/有界投递、死信/人工接管审计与受限清理已实现，新增 0063/0064 和三个 Job。当时 9 类 115 项（真实 PG 71）通过，零失败/错误/跳过；实际 System/调度/接收者和值守仍待部署验收。详见[第十四阶段](../reviews/2026-09-27-quality-master-durable-alerts.md)，不与本批测试数累加。

第十三阶段历史交付：主数据 UNKNOWN 有限人工核查、独立权限、双确认/证据、持久幂等审计、同事务停用排期及前端入口已实现。新增 0061/0062，当时后端 7 类 81 项（真实 PG 47）和前端 35 项通过，零失败/错误/跳过；类型检查仍 61 条范围外诊断，中台 0。第十六阶段未重跑前端；第十七阶段最新前端验证见上方，不累加旧结果。详见[第十三阶段](../reviews/2026-09-27-quality-master-manual-resolution.md)。

第十二阶段历史交付：Governance 主数据派发持久受理、一次性发布/周期排期与意图同事务、发送前恢复/令牌隔离、发送后 UNKNOWN 防重、取消发布/修改删除门禁及页面等待/待核查状态。新增 0060；原反例预期 5 实际 4 已转绿，当时后端 5 类 62 项（真实 PG 34）及前端 11 项通过，零失败/错误/跳过。10:58:29 前单个临时 PG/测试卷清理，未动业务库。见[第十二阶段](../reviews/2026-09-27-quality-master-durable-runtime.md)，不与本批累计数字。

第十一阶段历史交付：DataForge UNKNOWN 与领取耗尽告警持久账本，覆盖跨节点取消、超时恢复及补账；租约/令牌、有界重试、死信、人工处置审计和受限清理。新增 0058/0059 及三项 Job；当时 26 类 184 项（真实 PG 76）通过，零跳过。通知丢回执可重复，但不重跑业务；真实 System/调度部署仍待。详见[第十一阶段](../reviews/2026-09-27-quality-push-durable-alerts.md)，不与本批累加；Governance 告警由第十四阶段独立接入。

第十阶段历史交付：Kafka 确认/幂等重试边界、拒绝无回执发送、RabbitMQ 未路由检查与持久发布、异常会话废弃、执行前纯预检和前端默认值同步。原 6 项反例全部转绿，当时后端 25 类 109 项（真实 RabbitMQ 5、Kafka 4）及前端 23 项通过，零失败/错误/跳过；含真实 Rabbit ACK 丢失但队列已接收且不重发的反例。两种测试 broker 串行运行并清理，无新增 SQL、未操作业务库。接收方业务幂等/对账、MQ 高可用和完整应用多节点仍待。详见[第十阶段实现、升级影响与验证](../reviews/2026-09-27-quality-push-mq-confirmation.md)，不与本批累计测试数。

第九阶段历史交付：推送 UNKNOWN 人工核查接口、独立权限、两类受控证据、幂等审计和前端入口；仅确认“全部未生效/已完整补偿”且旧工作者及远端请求终止后允许人工处置。新状态 6 保留原错误，不改水位；同事务停用排期，避免解锁后自动重发。新增 0056、0057，后端 29 类 328 项（真实 PG 107）及前端 14 项为当时验证，不与本批累加。人工核查不等于自动证明外部结果，已生效/部分生效/仍未知不开放处置。详见[第九阶段实现、操作前置及验证](../reviews/2026-09-26-quality-push-manual-resolution.md)。

2026-09-26 第八阶段的排期原子受理、队列恢复和领取令牌隔离已保留；28 类 308 项为历史结果，不与本批累加，见[第八阶段](../reviews/2026-09-26-quality-push-durable-dispatch.md)。

上一批 2026-09-25 的持久 UNKNOWN、防重、取消/恢复、日志水位同事务、HTTP 重发修复及 UI 待核查保留；289 项后端/6 项前端为历史结果，不与本批累计，见[第七阶段](../reviews/2026-09-25-quality-push-persistent-unknown.md)。

## 1. 先看结论与使用边界

当前仍有明确代码/协议/产品适配缺口的是 **4 项：#12、#15、#17、#11**。#18、#19 的代码侧收尾已于 2026-09-29 完成，真实多服务验收、历史业务数据处置和部署切换仍单列上线事项。#7、#32、#33 按用户确认结项，不重新打开；四项不能全部解释为“只缺测试环境”。

原清单其他项的主要代码修复或用户结项已有记录；部分真实服务、浏览器和故障验收继续作为上线事项保留在第 9 节，不因此重新打开 #33，也不宣称生产级验收已经通过。#32 按用户要求保持“已完成”，但已知全量 SQL 基线限制必须保留。

本文初次整理只读核对关键源码、SQL 和已有报告；本次接续已实现并实测第 4 节推送修复，**没有重新执行全部 33 项的全量审计或回归**。历史测试数字均注明来源，不累加为当前通过总数。正文使用以下口径：

- **已实现**：当前代码或最新交付记录有对应实现；接手时保留，不重复造轮子。
- **待实现**：尚有明确代码、协议、操作流程或产品适配缺口。
- **待验收**：已有代码/局部测试，尚无对应完整部署证据。
- **待决定**：涉及业务政策或额外环境权限，不能由开发者自行扩大授权。

### 1.1 工作基准

| 仓库 | 本机路径 | 本次读取的分支 / HEAD |
| --- | --- | --- |
| 后端 | `E:/AnJieCloud/Workspace/DevSpace/datahub_projects/project-git/yudao-cloud` | `master-jdk25` / `a0cbed12`（2026-10-01 最后读取，需包含本轮未提交/未跟踪内容；未做远端同步） |
| 前端 | `E:/AnJieCloud/Workspace/DevSpace/datahub_projects/project-git/yudao-ui-admin-vben` | `master` / `66ec0e1f4`（2026-10-01 最后读取，权限页面 7 个修改文件及 2 个新测试需一并交接） |

真正基准是 **HEAD + 本地全部修改和未跟踪文件**。后端存在大量未提交修复和测试；前端初次整理时干净，第七阶段有 9 个变更/新增文件，第八阶段未改前端，第九阶段在原推送页面/API 上接入核查并新增 2 个测试文件，第十阶段另修改推送配置模型及其测试，第十一阶段未改前端。仅移交提交号会漏掉两仓库工作，交接时必须包含未跟踪文件，不要执行 reset、clean 或覆盖式同步。

仅处理数据中台。HRM、CRM、BPM、IM、Mall 等非中台模块不在本次修复范围；不能为全应用类型检查变绿而扩大到这些模块。共享组件确有本次调用链缺陷时，按组件归属修复并验证直接消费者。

第十二、十三阶段继续修改了主数据派发前端/API及新增测试；这些本地变更同样必须包含在交接中，不能只带前述第七至十一阶段文件。

根规则见仓库 `AGENTS.md`，技能唯一源为 `.agents/skills/yudao-datahub-development/SKILL.md`。Superpowers 不作为后续工作的前置条件。数据库产品适配、SQL 解析和写入事务机制归 DB Switch；推送、整改、共享来源的业务状态机归所属模块。

### 1.2 历史材料的优先级

- 原 33 项总清单（`2026-09-20-datahub-33-items-handoff.md` 已从仓库移除）：编号以本文第 2 节为准，不照抄旧章节的 N/P 状态。
- [八项执行顺序](../plans/2026-09-24-datahub-eight-items-execution.md)：用于任务排序。
- 本文引用的最新专项记录、当前源码和测试报告：用于判断当前缺口。
- 主清单仍保留早期“27 项未闭环”、部分章节“尚未接线”、旧迁移最高号等历史文字；这些不是本次现状。

## 2. 执行顺序及交付目标

沿用用户最后明确的顺序，不擅自将 #11 提回最前：

| 顺序 | 原编号 | 当前状态 | 下一交付重点 |
| --- | --- | --- | --- |
| — | #7 容量与扫描 | **已完成（2026-09-27 用户确认结项）** | 上线时仍需核定规模预算、真实慢库/热点压力和正式告警接收方；不计入剩余整改 |
| 1 | #18 多来源合并 | 处置绑定核查决定、批量处置、Supply 孤立旧 ADMIN 处置结论与只读一致性快照清点已实现（2026-09-29）；部署切换规程已落文档 | 真实多服务 A/B 来源处置验收与部署切换执行保留为上线事项；历史数据处置动作由授权管理员按规程执行 |
| 2 | #19 撤销及权限重算 | 历史处置结论、NOT_FOUND 受控重投（仅撤销/暂停）、四个生命周期入口普通审计、全端状态口径已收口（2026-09-29）；其余边界按用户决定保持现状并记录 | 真实 Supply/Asset 联调（`marketApplyExpireJob` Cron、mTLS、乱序回执）与浏览器验收保留为上线事项 |
| 3 | #12 整改后数据到达证明 | 部分实现；推送/主数据保护、PG16 INSERT/COPY 提交回执、完整批次与普通表物理身份/同快照诊断已有；启动/确认、旧编辑/删除竞争、通知提交边界、到期原子创建/普通故障隔离及0075持久公平扫描已补 | 到期再次触发政策、持久单项失败/UNKNOWN账本、跨批退避/耗尽/人工处置及历史残缺对账；通知多节点领取/远端幂等/UNKNOWN 对账与耗尽处置（旧数据及索引迁移待授权）；恢复/克隆代际、完整字段值范围与联合证据持久准入；其他产品/对象/写入模式、自动/已生效结果对账、真实通知及接收端验收 |
| 4 | #15 批准字段贯穿授权 | 部分实现；可选客户端 revision、独立续期政策及治理 schema 隔离已实现 | 全产品物理绑定、整体换库/规模及真实多服务端到端权限 |
| 5 | #17 SQL 全引用授权 | 受限投影已启用 25 个产品。MySQL 8.4.8 与 MariaDB 11.4.13 在 `lower_case_table_names=0` 下已证明精确表定位。MongoDB 与 Elasticsearch 的产品驱动不执行改写后的 SELECT，继续拒绝 | Doris、TiDB、StarRocks、GBase 8a 等其余产品的 `parseBound` 仍未证明。查询途中撤权/到期复验、缓存旧范围、只读账号/超时/行数与字节上限仍待。达梦只证明 `CASE_SENSITIVE=1`，金仓只证明默认大小写不敏感比较，OceanBase 只证明 MySQL 模式，Hive 只证明普通文本表，Greenplum 只证明单容器普通表 |
| 6 | #11 复检可比性 | 多产品有限路径已有，全部产品未完成。DB2 12.1.5.0 在 2026-09-29 再次以普通权限启动，四次都是 SQL1042C，实例没有起来，Q11DB 没有建成，未测代际。MongoDB 7.0.43 已实测但没有清空代际，未打开自动验收；既有集合指纹不随 deleteMany 变化。InfluxDB 3 Core 3.11.5 已实测但没有清空代际，未发关系凭证；既有 Int64 读取仍不能自动验收。SQLite 3.31.1 已实测但没有清空代际，未发关系凭证，也没有整数键治理链。Hive 4.1.0 已实测但没有截断代际，未发关系凭证；既有整数读取仍不能自动验收。SQL Server 2022 16.0.4295.3 RTM-CU27 已实测但没有截断代际，未发关系凭证；既有整数读取仍不能自动验收。Oracle AI Database 26ai Free 23.26.3.0.0 普通堆表已有原生身份、整数单键/2–4 列复合键、整数机构和 NOT_NULL 实库证据。Elasticsearch 8.15.3 已实测但没有截断代际，未发凭证。优炫 UXsinoDB 2.1.0.3 永久 heap 已有原生身份、整数单键/2–4 列复合键、整数机构和 NOT_NULL 实库证据。SQL Server 2000 8.00.760 SP3 已实测但没有截断代际，未发凭证。GBase 8a 8.6.2.43-R7-free 已实测但没有截断代际，未发凭证。神通 V8.0.251217 永久 heap 已有原生身份、整数单键/2–4 列复合键、整数机构和 NOT_NULL 实库证据。金仓 V008R006C009B0014 此前已有同类证据。Sybase 16.0 SP02 PL02 已实测但没有截断代际，未发凭证 | 继续按矩阵补其余产品。DB2 12.1.5.0 普通权限下 `db2sysc` 仍提前退出；在获准的独立测试库，或明确批准仅一个一次性容器使用特权之前，不能测清空代际，也不能套其他产品。MongoDB 7.0.43 没有 truncate 命令。deleteMany 不改变集合 uuid 或 WiredTiger ident；DROP/CREATE 才改变 uuid；collMod 改变 options 但不改变 uuid。计数随插入变化。不能把 storageSize 当成代际，也不能套 Elasticsearch 或 InfluxDB。InfluxDB 3 Core 3.11.5 没有 TRUNCATE，SQL DELETE、UPDATE 和 DROP TABLE 也不支持。delete table 只把活表改名为带时间戳的墓碑并留下旧行，再次写入复用原名，information_schema 没有表 id。WAL 序号随写入增加、删除不增加；catalog 日志序号在删除和写入时都会增加，而且不是 SQL 可见代际。刚写入时 parquet_files 为空。不能把 node-id、catalog uuid、WAL 序号或墓碑名当成代际，也不能套 SQLite 或 Hive。SQLite 3.31.1 没有 TRUNCATE；在找到每次清空都改变、插入和更新不改变且不会被再次插入或其他表复用的原生代际前不能自动验收。rootpage 在清空和 DROP/CREATE 后都复用，freelist 随插入复用，schema_version 只随 DDL 变化，data_version 随其他连接的插入变化。不能套 Hive。Hive 4.1.0 在找到每次 TRUNCATE 都改变、写入不改变且空表仍能读取的原生代际前不能自动验收；目录 inode、location、createTime 和 metastore id 不随截断变化，文件 inode 在截断后消失并在插入时新建，lastDdlTime 和统计随写入变化。不能套其他产品，也不能把单个 metastore 表 id 当成完整身份。SQL Server 2022 在找到每次 TRUNCATE 都改变、写入不改变且再次插入不会复用的原生代际前不能自动验收；不能用 SQL Server 2000 的结论代替这次测量，也不能把这次结果写回 2000。Oracle 的视图/全局临时表/索引组织表/分区父表/外部表/簇表/物化视图/VPD/加引号混写名仍拒绝；已经空着的表再 TRUNCATE 不改变 DATA_OBJECT_ID，ALTER TABLE MOVE 会改变代际。非整数 NUMBER、文本键、其他规则、物理恢复、PDB 切换和 RAC 未证明。parseBound 仍拒绝，不能套达梦、金仓、神通或 PostgreSQL。Elasticsearch 在找到每次清空都改变、写入不改变的原生代际前不能自动验收；没有 TRUNCATE，`_delete_by_query` 不改变 `index.uuid`，数据流 generation 在删流重建后复用。不能套序列号或段代际。优炫的视图/物化视图/UNLOGGED/分区/继承/fillfactor/规则/临时表/RLS、numeric、文本键、其他规则和物理恢复代际仍拒绝或未证明；全局临时表和外部表本轮没有建成。parseBound 仍拒绝，不能套 PostgreSQL、瀚高、Greenplum 或金仓。SQL Server 2000 在找到每次 TRUNCATE 都改变、写入不改变的原生代际前不能自动验收；不能套 SQL Server 2022。GBase 8a 在找到每次 TRUNCATE 都改变、写入不改变的原生代际前不能自动验收；不能套 MySQL、Doris、OceanBase 或 TiDB。神通的视图/全局临时/分区/非默认段/规则/外部表/本地临时/行策略、tinyint、文本键、其他规则和物理恢复代际仍拒绝或未证明；物化视图、索引组织表、列存、UNLOGGED、压缩、继承、簇和嵌套表在本构建上建不成。parseBound 仍拒绝，不能套 Oracle、达梦、PostgreSQL 或金仓。Sybase 在找到每次 TRUNCATE 都改变的原生代际前不能自动验收；不能套 SQL Server |
| — | #33 多节点验收 | **已完成（2026-09-27 用户确认结项）** | 未执行的双完整应用、真实引擎、Redis、网关/身份和故障注入保留为上线事项，不计入剩余整改 |

#7/#32/#33 不因等待部署参数而重新打开；优先继续 #18、#19。以后新增产品或改动的部署验收仍需覆盖对应链路，不能外推历史测试结论。

## 3. #7：已完成；保留上线验收注意事项

2026-09-27 按用户明确要求结项。这是工作清单状态决定，不是正式生产压测或通知接收方已经验收的声明。

### 已有，不需要重做

- ETL/ACCESS 已持久受理、按数据库时钟扫描，并共享租户与数据源占位预算。
- 冻结资源集合、UNKNOWN 保守占位、领取事务内再次校验预算已实现。
- 已修复热点来源通过不同资源组合挤占候选的问题；旧 ETL 无调用方的无界扫描重载已移除。
- 已修复采样年龄缺失、NaN、负数造成不报警的问题。本地真实 Prometheus/Alertmanager 投递和恢复已有验证。
- 最新历史记录为 146 项 Java（真实 PG 106）、3 项 Python、12 组 promtool 时序和 10 类告警触发/恢复；不是本次重新运行。
- 2026-09-27 新补验：11 类告警全部 firing/resolved；ScanFailures 保留五分钟窗口，分别核对 ETL/ACCESS；6 项 Python 自测通过，告警名称集合漂移门禁已修。临时三个监控容器/内部网络已清理。Java/promtool 未重跑，不与历史数字累加，详见[容量手册](../operations/dataforge-run-capacity.md)。

### 上线验收事项（不再计入 #7 未完成）

- [ ] 与部署方确定节点数、租户规模、数据源数量、同时到期量、目标库连接限制、允许等待时间和恢复目标；配置默认值不是容量承诺。
- [ ] 在接近目标规模下验证大量到期任务、慢库、热点租户、多资源组合及长期 UNKNOWN 占位，记录吞吐、等待时延、数据库负载和连接峰值。
- [ ] 验证采样卡顿、监控不可用不会挤占续租和停止线程；分别观测两个执行域。
- [ ] 确认所有节点预算一致；数据源预算按“租户 + 平台数据源 ID”计算，不能替代目标库账号/实例级配额。
- [ ] 接入正式监控接收方，确认告警路由、抑制、恢复通知及人工值守；全局 gauges 不能按应用节点直接求和。
- [ ] 对超时 UNKNOWN 建立核查与释放流程，不能为提升吞吐自动清空未知占位。

**上线验收目标：**超容量不丢已受理任务，热点不造成不可接受的长期饥饿，连接压力与恢复时延满足约定，正式告警可收到并恢复。尚无生产规模输入及正式接收方证据；如实记录该边界，不改变用户确认的 #7 已完成状态。

入口：`RunCapacityMapper`、`RunCapacityScalePostgreSqlTest`、`script/monitoring/dataforge-run.rules.yml`；详见[容量验证记录](../reviews/2026-09-25-dataforge-capacity-monitoring.md)、[运维手册](../operations/dataforge-run-capacity.md)。

## 4. #12：整改后的数据是否真正到达，及未知写入防重

这是当前代码接续点。**副本仍应保持 WAITING_DATA，不能因为同步显示成功、数量相同或元数据刚刷新就开放整改 PASS。**

### 4.1 已实现的六个阶段

1. 同步配置绑定及批次终态摘要持久化：0051。
2. DataForge 正式 API、受控 RPC 和 Governance 前后观察：0052。
3. 同目标的持久启动顺序与幂等提交后边界：0053。同次整改重试不移动原边界，旧启动晚结束不能冒充新批次。注意边界是首次复检延迟登记，不是原提交瞬间登记。
4. 实际搬数摘要、精确行数回执、跳过/DDL/字段裁剪/转换等诊断；短写不再算成功。
5. 共用 JDBC 全量/增量事务边界：异常不再隐式逐行重投，不以恢复 autoCommit 隐式提交；不确定结果保留 UNCONFIRMED 并处置异常连接。
6. PostgreSQL COPY 整批结构预检、原生回执、成功完成与取消分离、活动 COPY 取消失败时终止连接；前缀提交和短写误报反例已修。

保留上述实现及测试。第六阶段历史回归为 31 类 521 项，其中真实 PG 153，零失败/错误/跳过；这不代表随后新增的推送反例也通过。

### 4.2 第七阶段已实现，并由第八阶段增强：推送持久 UNKNOWN 防重

| 边界 | 本批实现 | 证据与限制 |
| --- | --- | --- |
| 状态/占位 | 新增 UNKNOWN=5；`DataPushRunCoordinator` 在租户内锁任务、检查未确认运行并持久领取，然后才提交本机队列 | 两协调器/连接争抢只领取一次；实际两执行器共用 PG 时只发送一次。本机 Map 只保留句柄 |
| 发送边界 | WAITING=1 → RUNNING=2 先提交；发送前取消/准备失败为 FAILED，发送后异常/取消/失联为 UNKNOWN | 第八阶段将队列拒绝改为保留同一 WAITING 意图退避，发送前失联允许重新领取；达到领取上限明确记发送前失败 |
| 恢复/回写 | 数据库时钟；租户、运行 ID、领取令牌、未过期期限及阶段 CAS；发送前允许接管，发送后只保留 UNKNOWN | 旧令牌的开始发送、心跳、进度、终态及拒绝回写均不能污染新领取；跨节点取消仍不代表物理终止 |
| 水位 | 成功日志与增量水位同一短事务；通知/血缘在提交后执行 | 水位写入故障使终态同回滚，仍保留运行占位；UNKNOWN 不推进水位 |
| 操作/清理 | 编辑、删除、重置水位、发布和领取共用任务行锁；只清理 SUCCESS/FAILED | 旧 UNKNOWN 即使不是最新日志也阻断；删除/重置服务入口实测拒绝 |
| HTTP | 未有接收端幂等契约前，不对异常/超时/5xx自动重发；保留旧配置字段但不再据此隐式重发 | 真实本机 HTTP 接收端已收到请求再回 503：由 3 次变为 1 次；不是完整 HTTP 幂等/投递成功证明 |
| API/UI | 新增 `blockingRunStatus`；页面“待核查”、禁止直接执行/重置等；日志支持 5 | 即使最新日志成功，较早未确认占位仍优先显示；实际父页挂载测试通过，非浏览器验收 |

第七阶段历史：2026-09-25 14:14 再现执行器红灯；14:21 的 HTTP 反例为“期望接收 1 次、实际 3 次”。修复后 28 类 289 项通过，其中 PG 72；没有降低 UNKNOWN 断言。该数字与本批及第六阶段均不累加。

### 4.2.1 第八阶段已实现：排期原子受理与持久派发

- 周期任务在任务行锁内重新校验租户、发布状态、排期代际、原到期时间与占位；同一短事务插入运行意图和推进 nextRunTime。按原槽位计算下一次，不从扫描当前时间跳过积压。
- 一次性发布与 WAITING 意图同事务；手动受理也保留持久记录。已提交后队列不可用或进程退出，另一节点扫描同一记录继续，不另建重复执行。
- `dispatch_protocol/token/lease_until/attempts/next_dispatch_at` 记录领取阶段。队列拒绝退避、发送前租约到期可接管；随机令牌隔离旧线程。默认 5 次领取耗尽后明确记录发送前失败，需要处理队列/节点后再安排执行，不保证无条件无限重试。
- 已进入 RUNNING 的租约到期不接管发送，转 UNKNOWN；不因旧心跳回收配置为 0 而关闭新协议安全回收。第九阶段已补有限人工处置，自动/已生效结果对账仍未实现。
- 原 `dataPushSyncJob` 先扫描持久等待，再受理到期任务，两组各最多 200 条；逐项恢复租户、单条失败继续其他任务并报告 XXL-Job 失败。无效 Cron 保留排期并报失败，不静默清空。
- **部署必须启用 `dataPushSyncJob`**，包括只有手动/一次性任务的场景，否则故障后等待记录不会自动重新派发；心跳只维护租约，不替代业务调度。

2026-09-26 10:36:43 真实 PG 原反例失败：排期推进但没有执行记录；修复后专项扩至 39 项，全套选择集 **28 类 308 项，真实 PG 91（推送 39、ACCESS 52），0 失败/错误/跳过**。覆盖排期/插入互相回滚、同槽并发、提交回执丢失模型、积压槽位、发布代际、真实服务与调度入口、队列拒绝后另一执行器恢复、令牌/租户隔离、升级重放及基线相关结构一致性。不是双完整应用或真实外部网络验收。

### 4.3 推送、派发后续清单

- [x] 租户内原子领取、独立短事务持久占位先于本机队列；本机 Map 不作为集群互斥事实。
- [x] 发送前与发送后分离；队列拒绝保留原 WAITING 意图退避，发送前失联可重新领取；发送后异常、取消或失联保留 UNKNOWN，后续手动/自动新执行被阻断。
- [x] 跨节点取消落库，心跳/进度/终态绑定运行身份；恢复重新检查阶段和心跳截止点，迟到回写不能覆盖已取消/未知状态。
- [x] 成功日志与水位同事务；外部发送、RPC、血缘和通知不在持锁事务内；水位故障完整回滚。
- [x] 清理、编辑、删除、重置、发布和领取门禁；UNKNOWN 不参加普通清理，API/UI 区分未知与运行中。
- [x] HTTP 通道取消没有接收端幂等证明的异常重发，补实际请求次数回归。
- [x] **MQ 确认与失败会话保护已修。** Kafka 缺省 all/幂等 SDK 重试，旧 1 不重试，0/非法在持久发送边界前拒绝，无 offset 不算成功。Rabbit mandatory + 持久消息 + return/confirm 检查，关闭自动恢复；两种通道异常后均禁止后续发送。真实 RabbitMQ 5 项（含网络丢 ACK）、Kafka 4 项及配置/执行器回归通过；前端默认与旧值语义同步。详见[第十阶段](../reviews/2026-09-27-quality-push-mq-confirmation.md)。
- [ ] **MQ 端到端与高可用仍待。** 未新增接收方持久批次幂等/结果查询契约；Kafka 真网络丢回执/leader 切换、多副本与 Rabbit quorum、多节点/TLS/SASL/完整应用故障尚未验。生产者会话幂等与 broker 接受不等于消费方业务完成，不支持未知结果自动重跑或推进水位。
- [x] **推送排期和一次性发布原子受理已修。** 新意图和排期/发布同事务，持久等待可恢复，领取令牌隔离；参见第 4.2.1 节。实际部署 XXL-Job、容量/积压及进程故障验收继续进入 #33，历史已经丢失的槽位不猜测补发。
- [x] **有限人工核查/处置闭环已实现。** 第九阶段新增 resolve-command、独立 resolve 权限、原因、终止/输出证据、当前操作者及幂等审计；前端执行日志加载最新凭证后提交。同事务改为“已人工核查处置(6)”并停用排期，不改水位、不伪报成功；迟到回写/旧排期拒绝，凭证不普通清理。仅确认全部未生效或已完整补偿可用，实际远端核查由授权人员负责；详见[第九阶段](../reviews/2026-09-26-quality-push-manual-resolution.md)。
- [ ] **自动对账及已生效结果处置仍待。** 证据编号的真实性不能由本批代码自动验证。已生效、部分生效未完整补偿、仍未知必须保留 UNKNOWN，不提供 SUCCESS/水位推进。缺失受控核查材料、旧工作者/远端请求未确认终止、其他在途或活跃 lease 均不得解锁。真实部署权限和值守、外部证据验证及成功水位收敛继续待验/待实现。
- [x] **关键终态持久告警已实现。** UNKNOWN/跨节点取消/超时恢复、发送前领取耗尽与告警同事务；独立 XXL-Job 补账、投递、到期接管，五次耗尽/无接收人留死信，人工重投或接管带幂等审计。成功必须获得正数站内信编号；丢回执可能重复通知，绝不重发业务数据。真实 PG 回归及模拟通知故障通过，见[第十一阶段](../reviews/2026-09-27-quality-push-durable-alerts.md)。
- [ ] **通知部署验收仍待。** 配置三项告警 Job、启用专用模板、确认服务身份/缓存/正式接收人及调度失败告警；没有完整 System/XXL-Job/双应用证据。普通连续失败和队列满仍是既有尽力通知，不能把本批称作所有通知可靠化。站内信无去重/查询契约，仍不提供恰好一次或已读保证。
- [x] **Governance 主数据派发持久运行协议已实现。** 第十二阶段新增协调器和 0060；运行意图先于入队，周期排期/一次性发布同事务，数据库租约与令牌隔离，发送前可恢复、发送后 UNKNOWN 防重；取消发布/修改删除门禁及前端等待/待核查已接线。最终 62 项后端（真实 PG 34）及前端 11 项通过，见[第十二阶段](../reviews/2026-09-27-quality-master-durable-runtime.md)。本机 Map 仅管理句柄，不是集群互斥事实。
- [x] **主数据有限人工核查/幂等审计已实现。** 第十三阶段在 Governance 独立落地 resolve、权限、证据与原子停用排期，新增 0061/0062；同命令重试返回原凭证，篡改内容/操作者或活动租约拒绝，其他 UNKNOWN 仍保持阻塞。前端先读最新详情，提交/身份/关闭/逆序响应均有保护；真实 PG 与页面挂载通过，见[第十三阶段](../reviews/2026-09-27-quality-master-manual-resolution.md)。核查不等于自动远端对账，仅未生效/完整补偿可处置。
- [x] **主数据关键终态持久告警已实现。** 第十四阶段独立 Governance 账本与运行终态原子提交，覆盖取消/失联/耗尽、历史补账和核查竞争；接收者冻结、无接收者死信、租约/令牌、有界重试、审计化人工接管及受限清理均已回归。新增 0063/0064、三项 Job 和专用 Notify 消费者，见[第十四阶段](../reviews/2026-09-27-quality-master-durable-alerts.md)。仅通知有界至少一次，绝不因通知重试而重发业务，状态 2 不代表已读/到数。
- [x] **主数据 HTTP 发送保护已实现。** 第十五阶段取消 Hutool 隐式重发路径，每批 body 禁止重开、禁止重定向，响应正文不物化/不进入异常；30 秒完整响应等待，停止时取消 future 并保留中断。地址/头/1–10000 批次在保存及 begin 前共用校验；后批未知保留已确认前缀，原子 UNKNOWN/告警和禁止重跑均实库通过。见[第十五阶段](../reviews/2026-09-27-quality-master-http-delivery.md)。2xx 只为传输确认，不是到数或接收方业务成功。
- [x] **主数据 MQ 确认与异常会话保护已实现。** 第十六阶段独立修复 Kafka all/幂等/有效回执及有界关闭、Rabbit mandatory/持久消息/return+confirm/禁用自动恢复、纯预检及前缀计数。Governance 真实 Rabbit 6 项（含第 1/2 批 ACK 丢失与中断）、Kafka 2 项和 PG 持久状态回归通过；见[第十六阶段](../reviews/2026-09-27-quality-master-mq-confirmation.md)。原生单节点不代表 Kafka 多副本/丢回执、Rabbit quorum、消费方幂等或完整双应用验收，继续进入 MQ 端到端/部署待办。
- [x] **主数据按运行编号独立停止已完成。** 第十七阶段新增 stop 接口和执行记录按钮，未发布手动任务可停；绑定不可变 recordId，发布/排期不变，发送前停止或发送后 UNKNOWN，持久 actor/时间/原因与告警同事务，旧回执不误停后续运行。0065 及实库/权限/前端验证见[第十七阶段](../reviews/2026-09-27-quality-master-run-stop.md)。停止不等于远端终止/回滚证明。
- [ ] **主数据派发尚未整体收口。** HTTP/MQ 接收端业务幂等和结果查询仍待；独立停止及取消发布均不保证远端物理终止。旧普通 FAILED、已删除/孤立凭证和历史漏槽位仍需核查，不直接改状态重跑；新核查入口也拒绝已删除任务。真实模板缓存、接收人、服务身份、三项告警 Job、调度失败通知和核查权限/值守仍需部署方验收。HTTP 网络/TLS/代理/端点策略、超大单行/LOB 字节预算需补验或完善。
- [ ] **扩大故障验收。** 原子受理提交后异常模型与第十阶段 Rabbit 真实网络丢 ACK 已测；Kafka 真网络丢回执、领取/开始发送/终态网络故障、完整应用崩溃/恢复、长队列、实际 DB/HTTP/MQ 与操作竞争仍待。两个真实执行器同一测试 JVM，不是双完整服务验收。

下一批推进 #12 结果对账/到数证明；真实通知部署、MQ 端到端及部署故障验收保留。人工核查需落实受控证据和值守，不能宣传为自动对账。不要重写已通过的推送协议、主数据持久运行、有限核查、关键告警、HTTP/MQ 发送保护及按运行停止。第 4.5 节到数证明继续保守拒绝；UXDB COPY 未知后回退已在第十八阶段修复，原生驱动/数据库验收仍待。复用 ACCESS/ETL 已验证原则，不复制专属业务表、枚举到 framework。

### 4.4 历史状态迁移及授权边界

用户已授权继续实现及一次性库验证。此前“草稿撤回/等待授权/不存在 0054”仅是历史快照；当前 `DataPushRunCoordinator.java`、0054 迁移和前后端实现均已存在。本批没有执行到任何业务库。

`0054_dataforge_push-unknown-guard.sql` 要求停止并排空全部旧推送节点、备份及维护窗口，禁止新旧协议混跑；提供只读影响统计。它将未删除的旧 WAITING/RUNNING，以及错误链明确包含 `UnconfirmedWriteException` 的 FAILED 保守转 UNKNOWN，新增未确认索引和列说明。真实 PG 已验证首跑、静止数据重放及选择性：不改普通失败、成功或已删除记录。其他历史失败/已删除凭证的外部结果仍需专项核查，不能由迁移推断。

此处理会停止相关任务自动重试；没有“自动改回失败重跑”的安全通用回滚。重复执行也必须先停机排空，不能把新协议活跃任务误当历史任务。授权生成迁移不等于授权升级业务库。结构测试只覆盖推送相关基线切片和新迁移，不代表完整新库基线可用；SQL 顺序见第 13 节。

第八阶段新增 0055，不修改已交付 0054：增加排期代际、派发协议/令牌/期限/次数/退避/槽位及索引，将协议 0 的旧在途记录保守转 UNKNOWN，禁止当作新协议发送前等待自动重领。首跑和重放只处理协议 0，不修改协议 1 的新等待记录；不补造旧已发布一次性任务或历史漏掉的周期槽位。仍须停机排空、备份和维护窗口；已升级库不要再重跑 0054。

第九阶段新增 0056 核查字段/唯一请求索引、0057 独立权限；不修改 0054/0055，不回填历史核查决定、不自动授予角色。正式管理员须独立授权核查人。只能把有证据的无生效/完整补偿记录从 5 单向处置到 6，并停用任务；不提供自动 down 回滚抹除审计。两脚本仅在一次性 PG 验证，未升级业务库。

第十一阶段新增 0058 告警/人工处置账本及索引、0059 站内信模板；只恢复告警投递，不改变原执行结果。任务接收人缺失时保存死信，不能伪造默认管理员。须部署 `dataPushAlertRelayJob`、人工专用 `dataPushAlertDispositionJob` 和受限清理 `dataPushAlertCleanJob`；后两者不解除业务 UNKNOWN，不推进水位。维护窗口、完整模板表/序列、缓存刷新及运维权限前置见第 13 节和专项记录。

第十二阶段新增 0060 主数据派发持久运行。旧协议在途及明确 `UnconfirmedWriteException` 失败转 UNKNOWN，普通历史失败和新协议记录不变；维护窗口先停机排空旧主数据节点、备份，禁止混跑。发布/领取/配置/取消共用领域门禁，未提供无证据解锁；部署即使仅手动派发也必须启用 `masterDistributeJob`。

第十三阶段新增 0061 主数据核查审计字段及唯一请求索引、0062 独立 resolve 权限；不回填历史核查、不自动授予角色。停旧节点并备份后，完整菜单/序列/主数据运行按钮必须存在；缺前置/冲突拒绝。全部节点与前端统一升级，管理员独立授权并刷新缓存/会话。仅一次性 PG 验证，未升级业务库。

第十四阶段新增 0063 主数据关键告警/人工处置账本及扫描索引、0064 专用站内信模板。0062 后停旧节点、备份并顺序升级；完整模板表/序列必须存在，模板禁用/内容冲突拒绝。统一应用并刷新模板缓存，启用 Governance 三项告警 Job 和原 masterDistributeJob；实际接收者/调度失败通知/值守需要部署验收，未自动配置或发送业务通知。

### 4.5 还没做完的到数证明与其他写入模式

2026-10-01 最新接续：**历史到期动作只读清点工具子项完成**，执行方式为已授权DBA psql，不新增浏览器入口/权限。按租户/动作分页、只读单页快照报告缺失、逻辑删除与候选观察；没有直接后续单绑定，任何候选保持UNPROVEN。最终467项（真实PG267）限定回归及完整psql包装验证通过，5通知诊断过滤且未修。无新编号SQL/生产Java/前端变化，未清点业务库；[操作规程](../operations/quality-exception-expiry-inventory.md)明确权限、分页截断、字段含义和现场核查/处置授权，[专项记录](../reviews/2026-10-01-quality-expiry-inventory.md)列过程失败和未验范围。以下为历史。

2026-10-01 最新接续：**旧编辑客户端读取版本保护子项完成**。后端436项（真实PG236）和前端20项实际通过；只关闭旧PUT/API契约的陈旧覆盖缺口，不增加编辑UI。原创建兼容，旧客户端须携带读取时版本且不能自动换版本重投。全应用类型仍61条历史诊断；5项通知诊断排除、未修。无新SQL，最高0075，未操作业务库；细节、过程红测、未验范围见[专项记录](../reviews/2026-10-01-quality-update-client-version.md)，下方为历史。

本轮收尾：另1项Spring真实Mapper/租户/事务装配测试零失败/错误/跳过通过，未并入406项；最高迁移0075，临时PG自有schema为0后已核验精确容器并删除，镜像保留。扫描迁移已在临时库验证，业务库未执行。

2026-10-01 最新接续：**持久公平扫描子项完成**。406项限定回归（真实PG223）全部通过；0075新增租户扫描位置、固定轮次上界及空周期结束标记，首全故障页/预约后消失/提交回执丢失/持续新增/较小ID新到期/多协调器/租户隔离等真实PG模型回归。位置不是业务成功或失败/UNKNOWN结果账本；没有改变再次到期政策或通知协议。详情和SQL顺序见[专项记录](../reviews/2026-10-01-quality-expiry-fair-scan.md)，下方检查点为历史。

2026-10-01 最新接续：**例外到期同批普通故障隔离、失败报告与安全停止子项完成**。375项限定回归（真实PG192）通过，5项通知诊断未修/未计通过；查询前置上下文和被回滚异常覆盖的应用中断均有先红后绿证据，见[本轮专项记录](../reviews/2026-10-01-quality-expiry-batch-failure-isolation.md)。不将批内继续等同持久失败/人工处置或跨页公平扫描，四项整体状态不变。

2026-10-01 最新接续：**例外到期原子创建与已完成项扫描推进子项完成**。限定回归 330 项（真实 PG 147）及另 6 次索引有/无双节点并发均通过；未解决通知 5 项已知故障，也未改变再次例外到期政策、持续故障隔离或自动修复历史。详见[本轮专项记录](../reviews/2026-10-01-quality-exception-expiry-atomicity.md)。以下删除/编辑检查点为历史。

2026-10-01 最新接续：**旧删除执行期竞争与工单/关系原子性完成**，含关系静默写入失败后置验证；限定回归 298 项（真实 PG 115）通过。通知诊断 5 项全部先红且未修，最终命令明确按标签排除，不能计为通过。历史 UNKNOWN 隔离/原子领取/索引迁移被自动审批阻止，需用户明确授权；[专项记录](../reviews/2026-10-01-quality-delete-concurrency-and-notify-blocker.md)给出风险和精确范围，不执行共享/业务数据库。

2026-10-01 当前接续：**旧编辑执行期竞争、通知提交边界修复完成**。最终 8 类 282 项（真实 PG 99）全部通过；[专项记录](../reviews/2026-10-01-quality-edit-and-notify-commit-boundary.md)列出准确验证范围和剩余风险。通知由现有 `qualityWorkOrderNotifyJob` 下一轮发送，部署须启用任务和有效模板；原推荐五分钟 Cron 不等于五分钟内必达，也不等于任务已配置。不改用户已有 Job 文档或调度配置。

2026-10-01 接续：**启动原子性与关闭前平台实际执行证据复验子项完成**。新增两个写入行数门禁，以及原决定与重新判定的逐问题事实精确比较；既有阈值政策、原决定、旧请求幂等不改变。8 类 272 项（真实 PG 89）通过，见[专项记录](../reviews/2026-10-01-quality-confirm-execution-binding.md)。这不是外部源数据的持续新鲜性、恢复代际或副本到数证明，整项仍未完成。

- [x] 复测启动 attempt 与 submission 实例绑定写入各为 1 行；失败回滚运行、轮次、绑定、命令与状态，并且不触发提交后派发。
- [x] 确认旧 PASS 前复验实际检测实例、冻结定义和基线/当前审计，原完整 checks 与当前事实必须一致；缺失、变更、重复审计或不同但仍为 PASS 的事实不能关闭工单。
- [x] 旧编辑入口 `updateWorkOrder` 与提交/确认共享工单锁和版本门禁，校验后不能改写已关闭工单绑定；真实问题/关联 Mapper 的双协调器交错、成功推进版本、零行写入、关系写入失败整单回滚均通过。请求执行期竞争已覆盖；本轮另补客户端读取版本保护，见下条限定子项。
- [x] 工单 `enqueueNotify` 仅持久台账、不在工单事务内发远端；提交后另一协调器可扫描，回滚不发，事务内误调拒绝，业务失败和非法回执不记 SENT。通知远端是替身，不冒充真实通知服务/调度/送达验收。
- [ ] 通知多节点原子领取/租约、提供方持久幂等和按键对账、远端结果未知及重试 8 次耗尽后的人工处置。当前运输异常仍沿用 FAILED 退避，可能重复通知；不能保证恰好一次，需要正式提供方契约配合。已新增并执行 5 个真实 PG 诊断，均失败；迁移补丁被自动审批拒绝，未落盘，授权边界见专项记录。
- [x] 旧工单删除与提交/确认的竞争、独立删除权限/既有可见对象范围、实际历史保护、严格逻辑删除和关联抛错/静默失败回滚。16 项删除用例与直接工单回归通过；未将本地双协调器或 Guard 替身等同部署/权限 RPC 验收。
- [x] `expireExceptions` 逐单事务、同租户父单锁、候选资格复验、审计/新单/完整问题关联/通知意图原子性及已完成项不占第一页；真实故障先红后绿，索引有/无并发均验证。只关闭此限定子项，不视为通知送达、到数或恢复证明。
- [ ] 同原单重开后再次例外到期仍被任意历史 EXCEPTION_EXPIRED 动作挡住；是否允许每次提交独立触发需确认领域政策，本轮沿用旧语义。
- [x] 同批普通执行异常隔离：失败项事务退出后继续其余候选，批末真实失败；取消/中断、事务完成异常和上下文污染停止，原始诊断及关联完整保留。真实PG及原Job/租户切面替身环境验证通过；不能把所有异常概括为确定回滚。
- [x] 非TSE事务边界异常停批与原异常恢复：普通begin/commit/rollback及真实提交同步回调异常不进入后续候选；回滚覆盖的取消、中断和直接Error保留优先语义。正常业务失败隔离兼容、已提交结果保留、新节点防重均验证，最终535项（真实PG335）通过；不等于进程崩溃持久UNKNOWN登记或实际断连验收。
- [x] 到期扫描的持久公平推进：0075租户游标、固定上界、空轮次结束、首全故障页与新节点回绕恢复均验证；保留20条上限，扫描位置不表示执行成功。并发预约不是业务唯一执行租约，仍依赖原父锁和完整动作幂等。
- [ ] 到期失败的持久单项失败/UNKNOWN账本、跨批退避、耗尽/人工处置及无动作/孤立后续单覆盖；结果绑定与已交付只读工具不能替代执行失败台账，也不自动处置历史数据。
- [x] 历史已提交到期动作的只读清点工具与规程：报告原单/提交缺失、逻辑删除、当前同根候选/关系/通知意图、重复/分页和截断；真实PG31项新增覆盖及完整psql SELECT-only角色验证。仅交付工具，业务库未执行；候选永远UNPROVEN、不自动补建或重投。
- [x] 新到期动作直接结果引用及创建时问题多重集/原接收人持久化：0076及同事务CAS/复读、故障整笔回滚、旧结构首跑/重放、相关基线切片、租户目标唯一性；只读v2独立查询精确目标及冻结快照。限定子项最终514项（真实PG314）通过，不追认旧历史、不证明到数/恢复/消息送达。
- [ ] 现场历史全量清点、缺失/清理/人工删除的可信审计核验、旧动作与后续单绑定证明，以及精确授权后的数据处置。五列全空的旧动作没有followUpId，当前同根唯一性/关系/owner/通知状态不能补造冻结证据；新引用也不能替代现场核查。未产生动作的到期提交和孤立后续单不在本工具覆盖范围。
- [x] 旧编辑 API 客户端长期持有旧版本后的顺序覆盖保护：必带读取时 `expectedVersion`、严格锁内比对、零版本合法、拒绝缺失/过期/超前/负数/溢出和JSON小数截断；真实双协调器同版本竞争仅一笔名称/关系完整提交。前端 UpdateRequest 不自动刷新/重投、不改输入，创建无版本仍兼容。当前活动页面仅创建，没有新增/验收编辑UI；436项后端与20项前端限定回归通过，详见本轮专项记录。

2026-10-01：**持久复测决定与确认准入子项已增强**。v3 决定绑定 attemptId/attemptNo/requestKey、提交人/提交 revision/完整内容摘要、taskId 和问题集合；逐问题还绑定对象/规则/基线/机构身份。确认时重新读取全部关联问题，缺失记录不能静默丢弃；决定/提交/工单更新必须各影响 1 行。旧 v2 或缺绑定 PASS 不自动升级，必须新复测。此修复不等于下面的可信恢复代际或完整字段到达证明；副本读取仍缺证明时保持 WAITING_DATA。

- [ ] 在已实现的 PG16 普通表物理身份和提交见证上补可信恢复/克隆代际及其他产品/对象；OID/文件号/路由仍可能在恢复后复用，组件公共契约不夹带工单业务字段。
- [x] PostgreSQL 16 INSERT 顶层 xid、同连接写前后复验、提交状态/连接清理成功后交付已接通；逐表前 16 批诊断清单及总/见证计数经 DataForge JSONB/API 留存，旧/缺失/COPY 不补造见证。见[第二十阶段](../reviews/2026-09-27-quality-commit-receipt.md)。路由摘要不是恢复代际，截断不是完整范围，不能单凭新回执让副本通过。
- [x] PostgreSQL 同一规则的基础统计、机构/单列/复合键与问题主键已绑定同连接只读一致快照；复验并完成清理后才保存，保存点隔离可选读取失败，重试不拼接旧凭证。真实 16.15 与既有整改复检回归见[第十九阶段](../reviews/2026-09-27-quality-read-snapshot.md)。这是读取侧基础，不是副本到数证明。
- [x] PG16 INSERT 顶层提交已能在质量规则的实际连接/同一快照内核对，区分可见性及路由不符；最多 16 份、只对完整清单诊断，绑定运行/attempt/rowVersion/批次，观察变化或清理失败不留成功凭证。见[第二十一阶段](../reviews/2026-09-27-quality-commit-visibility.md)。只证明该次事务观察，不证明恢复代际、对象/字段值、全量到数或跨节点副本支持。
- [x] PG16 INSERT 全部已确认批次的常量空间摘要及实际快照区间核对已接通；18 批真实同步/异步迁移、JSONB 与质量入口、10 万批摘要有界性通过。旧样本仍限 16 条且保留 truncated，新范围路径每次最多 20 个运行；缺失/矛盾/重复或中间在途事务不冒充完整。见[第二十二阶段](../reviews/2026-09-27-quality-commit-range.md)。这不证明字段值、恢复代际或整表原子性。
- [x] PG16 二进制 COPY 也能交付实际顶层提交回执、完整批次摘要并在质量规则快照核对；确认 COPY 协议退出后才查询，行数/提交/状态/清理失败不发回执，14 类故障不重投。普通 select/insert 测试账号验证通过；18 批同步/异步迁移、JSONB/API 和实际质量入口已回归。见[第二十三阶段](../reviews/2026-09-27-quality-copy-commit-receipt.md)，不外推 Greenplum/Kingbase/UXDB，不证明值无损或副本准入。
- [x] PG16 普通持久表物理身份与 INSERT/COPY 回执、所有批次及实际读表已贯通；同名重建/清空/重写返回对象不符，旧回执/未支持关系不补造 MATCH，普通账号、特殊标识符、旧 catalog 快照和提交后变更均实测。见[第二十四阶段](../reviews/2026-09-27-quality-relation-commit-binding.md)。MATCH 不是恢复代际/字段值或到数证明。
- [ ] 将已实现的事务范围/普通表对象观察与可信恢复代际、完整字段值范围联合，逐产品验证真正副本/代理和快照语义。跨路由、缺证据或超过运行观察窗口仍拒绝；不凭 `VISIBLE` / `RANGE_VISIBLE` / 对象 MATCH 开放副本 PASS。
- [ ] 将完整到数联合证据纳入 Governance 持久准入；工单/提交/attempt/当前问题及平台实际 checks 绑定已完成限定子项，但恢复代际、字段值范围与到达证明仍待。缺证据继续等待/未知，不复用别的轮次通过。
- [ ] 分别证明全量、增量、DDL_ONLY、定制 SQL、多源、部分表失败、字段裁剪/NULL 降级/转换等语义；行数相同不能证明值无损或范围完整。
- [x] `UxdbCopyWriterImpl` 异常后 `super.write` 回退 INSERT 已移除；独立事务、完整回执、未知结果、取消/异常连接和安全引用已接通共用组件。第十八阶段 20 项单测与 11 项真实 PG 协议模型回归通过，见[专项记录](../reviews/2026-09-27-quality-uxdb-copy-outcome.md)。
- [ ] UXDB 原生驱动/数据库验证：真实 unwrap/getCopyAPI、手动事务、COPY 回执、中断/abort/池淘汰、提交与节点故障；本轮未确定可用介质/许可或隔离实例，已询问，PG 协议模型不替代原生产品证据。
- [ ] Greenplum 使用共用 COPY 实现，但尚无本阶段原生产品验收；PostgreSQL 通过不能代替 Greenplum/UXDB。
- [ ] 不把单批事务成功宣传为整任务原子性；后续批次失败不回滚已经提交的前批。

**完成条件：**推送/派发未知结果不会自动重发或消失；确定未发送的失败可以安全恢复；日志/水位一致；真实副本仅在同次读取能证明目标数据到达后允许对应新轮次收敛。需要真实 PG 双工作者、跨租户、队列拒绝、响应丢失、心跳竞争、取消前后、迟到回写、迁移重放及目标产品测试。

证据：[本批推送实现](../reviews/2026-09-25-quality-push-persistent-unknown.md)、[历史推送反例](../reviews/2026-09-25-quality-push-unknown-reproduction.md)、[COPY 修复](../reviews/2026-09-25-quality-copy-outcome-reproduction.md)、[JDBC 修复](../reviews/2026-09-25-quality-jdbc-transaction-reproduction.md)、[提交边界](../reviews/2026-09-25-quality-sync-submission-boundary.md)、[实际搬数摘要](../reviews/2026-09-25-quality-sync-transfer-outcomes.md)。

## 5. #15：批准字段、客户端版本与续期政策

### 已有

批准物理列和绑定证据冻结、门户查询快照及返回前复验、多申请兼容字段并集、生命周期 CAS、服务端提交竞争保护、续期创建幂等和 0050 在途唯一性均已有实现。PG SQL/表列表/列清单精确定位已有实库记录。不要重做批准字段协议，也不要退回动态 ALL。

### 未完成

- [x] **客户端所见 revision 贯穿写操作。** 2026-09-28 按用户“带了就校验”实现内容版本与来源版本的可选回传及冲突拒绝，前后端贯通，缺省兼容原 CAS；见[专项记录](../reviews/2026-09-28-sharing-client-revision.md)。新建、续期、删除、需求侧未加；如需强制须在客户端升级后改为必填。
- [x] **父子续期政策（本轮明确范围）。** 用户确认子申请独立审批、独立来源，父申请暂停/撤销/到期不级联。子申请仍重新审批，继承用途、义务、法律依据、应用/API 与冻结字段范围；补正不可改变这些内容，重新提交只更新本轮快照 revision，不把新发布字段加入原冻结集合。新 SLA 依据当前申请类型重新计算，清除父申请补正说明。其他生产级跨服务审批/来源行为仍归下方完整链路验收，不以本地替身证明远端授权。
- [ ] **全产品物理绑定。** PG 已证明的 schema/引号/大小写语义不能套给 MySQL/MariaDB 或其他产品。与 #17 共用组件能力，验证后逐产品开放。
- [x] **治理 schema 范围（PG/MySQL/MariaDB 受限 SELECT）。** 0074 增加 schema 及核验记录；表/字段策略按租户、数据源、精确 schema/table/角色匹配，精确列名不去引号或折叠猜测。历史有效 NULL-schema 策略会拒绝数据源访问，管理员在页面显式核验整组绑定，禁止自动合并/覆盖。结果返回前以原始 SQL 和当前策略重建执行 SQL，可信行策略读取隐藏列不会给用户提权；策略变化或执行 SQL 不一致则拒绝。其余产品与非 SELECT 不在已证明范围，继续作为 #17 未完成项。
- [ ] **整体换库和规模。** 数据源整体指向另一库后的版本/绑定失效、大列表分页及来源上限需要专项核对，不能只检查表名相同。
- [ ] **完整链路。** 真实审批 → 冻结交付 → Supply 聚合 → 查询 → 续期/暂停/到期/撤销；UI 实际可用范围应与最终聚合一致。

**完成条件：**A 打开旧页面，B 更新后 A 不能静默覆盖；重复相同请求幂等，内容冲突明确；父子行为符合已确认政策；任何实际返回字段不超出当前有效批准范围，查询途中变更时旧结果不释放。

入口：Asset `MarketApplyServiceImpl`、`MarketApplySaveReqVO`、`MarketQuerySnapshot` 相关调用；前端 `apps/web-antdv-next/src/api/asset/market/index.ts` 和对应申请/审批/续期页面。接手前读前端自身 AGENTS 和技能引用。

证据：[生命周期](../reviews/2026-09-23-market-lifecycle-concurrency.md)、[多申请读取](../reviews/2026-09-23-market-multi-apply-access.md)、[服务端提交竞争](../reviews/2026-09-23-market-submission-concurrency.md)、[续期幂等](../reviews/2026-09-23-market-renewal-idempotency.md)。

## 6. #17：SQL 所有引用列授权及全数据库支持

### 已有与真实边界

DB Switch `RestrictedSelectGuard.restrictProjection` 已校验支持语法中的 SELECT/WHERE/ORDER BY，并执行前投影；门户和网关已有接线，不是“解析器仍完全没做”。精确 `parseBound` / `parseTableReference` 和治理 schema 链目前开放 PostgreSQL、MySQL、MariaDB，其他产品继续拒绝，不能从投影支持推断全链支持。2026-10-01 新增可信行条件合并：用户引用先按批准列校验，两侧显式布尔分组避免 OR 绕过，分别使用三种真实产品验证；治理返回结果使用双 SQL 复验。Doris 4.0.8 的大小写敏感 relation 匹配子项已修，列名/别名规则保持原有语义，`parseBound` 和精确元数据链仍未开放。

### 未完成

- [ ] 按第 10 节全部 27 产品，逐项证明 catalog/schema、大小写、引号、转义、别名、同名对象、临时对象遮蔽和实际查询对象一致。
- [ ] 明确支持语法集，检查所有可能引用列的位置；支持的过滤/排序必须校验，未证明的函数、表达式、子查询、JOIN、GROUP/HAVING、CTE 等明确拒绝，不为了兼容直接放行。
- [ ] 通配符在执行前展开为批准物理列，不以查询后删字段代替授权。
- [ ] 不能只按输出别名校验，防止未授权列藏在条件、排序或表达式里；元数据、绑定对象和实际执行使用同一证据。
- [ ] 在 DB Switch 扩展技术契约与逐产品实现，Asset/Supply/DataForge 保持薄适配；不要各自复制解析器或产品 switch。
- [ ] 数据库账号只读、超时、行数/字节上限、取消及日志脱敏同时验证；SQL 语法正确不等于安全。
- [ ] 补直接消费者、真实数据库和查询途中撤权/到期的最后一次复验；缓存不能释放旧范围。

**完成条件：**只批准 id 时，`SELECT id FROM t WHERE salary > 10000` 和按 salary 排序都在数据库执行前拒绝；合法批准查询通过；同名不同 schema 不串授权。按产品提交实际驱动、测试版本、支持语法和限制，不能批量改枚举后标 27 产品完成。

### 2026-09-28 排查接手（Step 2–4 已完成；下述探测/计划保留为历史）

**范围与调用方。** `RestrictedSelectGuard.restrictProjection` 已对 PostgreSQL、MySQL、MariaDB 生效（`dialect()` 只映射这三种），生产调用方：DataForge `DatabaseApiImpl.restrictToAllowedColumns`（用户 SQL 预览，`executePreview` 以 `setMaxRows` 限行，不包裹 SQL）和 Supply `DataApiExecutor.restrictToApprovedColumns`（表模式由系统拼 SQL，计数时包成 `SELECT COUNT(*) FROM (<sql>) t`）。`parseBound`/`parseTableReference` 仍只开放 PostgreSQL。治理行权限 `SafetySqlPermissionService.injectWhere` 用 JSqlParser 重解析重序列化，会丢弃注释。

**解析层探测结果（仅调用 guard，未在真实库执行）。** 以下写法当前被放行，均不在文档声明的“列、字面值、参数、基本比较/布尔组合和列排序”白名单内：

| 问题 | 产品 | 现象 |
| --- | --- | --- |
| 结尾注释原样保留 | PG / MySQL | `... WHERE name='x' -- c`、`/* c */`、MySQL `# c` 出现在输出 SQL 末尾；调用方若再拼接 SQL 会被注释吞掉（当前两调用方未因此越权，但属潜在隐患） |
| 会话/系统变量 | MySQL | `@@version`、`@x` 以 `SQLVariantRefExpr` 通过（与 `?` 参数同类型），可泄露服务器信息 |
| 查询修饰与锁 | MySQL | `FOR SHARE`、`LOCK IN SHARE MODE`（只读预览加行锁）、`SQL_CALC_FOUND_ROWS`、`HIGH_PRIORITY`、`STRAIGHT_JOIN`、`SQL_NO_CACHE`、`PARTITION (p0)`、`USE INDEX` 通过 |
| 采样与特殊字符串 | PG | `TABLESAMPLE SYSTEM(n)` 通过；`$$x$$` 以 VARIANT 通过 |
| 反斜杠转义 | PG / MySQL | 输入 `'x\' or secret is not null --'` 被重写为 `'x\'' or secret ... --'`（PG）/`'x'' or secret ... -- '`（MySQL），按分析是单个字面值，**必须用真实库差异测试确认**，尚未证实越权 |

已确认被拒绝（保持）：未授权列（含双引号 `"secret"` 被当标识符）、`<=>`、`REGEXP`、`ESCAPE`、`COLLATE`、行构造比较、函数/类型转换、PG `FOR SHARE/KEY SHARE/UPDATE OF`、`ONLY`、MySQL `FOR UPDATE`、`INTO OUTFILE`（异常信息不友好：`not support visitor type`）。Druid 1.2.28 词法：MySQL `/*! */` 与 `/*+ */` 为 `HINT` 令牌、普通注释计入 `Lexer.getCommentCount()`，MySQL 词法把 `\'` 视为转义，PG `$$..$$`/`$1` 为 `VARIANT`。**目前没有发现已证实的越权读列。**

**已写但未运行的测试**（`yudao-framework/yudao-spring-boot-starter-biz-dbswitch/src/test/java/cn/iocoder/yudao/framework/dbswitch/core/service/`）：

- `RestrictedProjectionDifferential.java`：23 条攻击输入；对 guard 接受的 SQL 在各会话模式下真实执行，断言只返回批准列、带 WHERE 的查询不返回行（夹具所有行 `name='row'`、`secret` 非空，因此任何返回行都说明未批准谓词生效）。
- `RestrictedProjectionPostgreSqlTest.java`（系统属性 `datahub.test.postgres.url`，`standard_conforming_strings` on/off）、`RestrictedProjectionMySqlTest.java`（`datahub.test.mysql.url`）、`RestrictedProjectionMariaDbTest.java`（`datahub.test.mariadb.url`），后两者覆盖默认、`ANSI_QUOTES`、`NO_BACKSLASH_ESCAPES` 及组合。每例建随机 schema/库并在结束时删除。

**待清理资源（接手前先核对，属本任务所有）：**

- 临时探针目录 `yudao-framework/yudao-spring-boot-starter-biz-dbswitch/src/test/java/probe/`（3 个 `Tmp*ProbeTest.java`，不属交付，删除）。
- 测试容器（只绑定 127.0.0.1，未接触业务库）：`q17-pg-20260928`（postgres:16-alpine，端口 15461，卷 `d8148138bffdbcdbcda58817ac70c7a0714c7d0bb5e1ef3a24f357efd19c3d7e`）、`q17-mysql-20260928`（mysql:8.4，8.4.8，`lower_case_table_names=0`，端口 15462，卷 `1c9635b0df360238cd98725bc7f48b5ba0102cbca3040d1eeff1fba4d6b55b6d`）、`q17-mariadb-20260928`（mariadb:11.4，11.4.13，端口 15463，卷 `c0aabd5ff314d8b1512cc7d8ab514b3ce260774f2bb5bbd11c314934f6abfe44`）。可复用后按标签/挂载核验再删除容器与卷。

**执行计划：**

1. 先删探针目录，再对当前代码跑三类差异测试取得基线（红/绿均如实记录）。Windows `mvn.cmd` 会把参数中的 `&` 当命令分隔符，JDBC URL 不要带 `&` 查询参数；MySQL 容器为 root 空口令，URL 用 `jdbc:mysql://127.0.0.1:15462/`，必要时单独确认驱动是否需要 `allowPublicKeyRetrieval`。
2. 在 `RestrictedSelectGuard`（DB Switch 组件，不在业务模块复制）按产品收紧并补单元测试（先红后绿）：拒绝任何注释（词法 comment 计数或 `HINT` 令牌）、拒绝 MySQL 会话/系统变量与 PG `$$` 字符串（只保留 `?`/PG `$n` 参数）、拒绝 MySQL 查询修饰/锁子句/分区/索引提示及 PG `TABLESAMPLE`、MySQL 字符串字面值含反斜杠时拒绝（语义随 `NO_BACKSLASH_ESCAPES` 变化）；`INTO` 给出明确中文错误。LIMIT/OFFSET/FETCH 保留。
3. 三种真实库复验差异测试；跑 DB Switch 既有 `RestrictedSelectProjectionTest`、`RestrictedSelectGuardTest`、`MysqlTableReferenceTest`、`RestrictedSelectProjectionPostgresTest` 及直接调用方 DataForge（`DatabaseApiImpl` 相关）与 Supply（`DataApiExecutor`/`DataApiQueryContractTest`）回归；组件改动需最小上下文与受影响消费者验证。
4. 写专项记录 `docs/reviews/2026-09-28-quality-restricted-projection-hardening.md` 并更新本节；本项预计无平台 SQL。仍待：其余 24 个产品、`parseBound` 扩到 MySQL/MariaDB、查询途中撤权复验等按上方未完成清单继续，不以三产品结果外推。

**执行结果（2026-09-28 完成 Step 2–4）：** 探针目录已删除。加固落在组件 `RestrictedSelectGuard`（`rejectUnsafeLexemes` 词法预扫 + `rejectUnsupportedQueryClauses`/`rejectUnsafeTableSource` AST 校验），上表所有放行项均已拒绝，`INTO` 给明确中文错误，LIMIT/OFFSET/FETCH 保留；业务模块未复制解析器、无 framework 变更、无平台 SQL。新增 `RestrictedProjectionHardeningTest`（19 项，先红 13、后绿 19），重写差异公共类并把三类真实库测试断言改为 `assertEquals(0, accepted)`，并修正早于本任务的 `MysqlTableReferenceTest` 陈旧断言（陈旧 MARIADB 反例换为 TIDB 并补 MARIADB 正向断言）。验证：整模块 DB-less **978/0/0（200 门控跳过）**；三类真实库差异测试（q17 三容器，各会话模式）**3/3 全绿、0 跳过**；消费者 **Supply 48/0/0、DataForge 46/0/0**（首轮 `-pl` 未 `-am` 的 Mockito/`NoClassDefFoundError` 两处陈旧构建报错经 `-am` 刷新后全绿，非本改动）；`python tools/quality/run_all.py` 通过。注意：把 `datahub.test.postgres.url` 指向 q17 会额外激活需要 `dataforge_test` 角色的 `RelationCommitPostgreSqlTest`（q17 未建该角色）报错，属另一门控用例夹具前置、与本改动无关，故整模块按 DB-less 跑。q17 三容器（15461/15462/15463，无匿名卷）在复用验证后按名称/镜像/端口核验并删除，未触及业务库或他人资源。详见[本轮记录](../reviews/2026-09-28-quality-restricted-projection-hardening.md)。#17 整项仍未完成（其余 24 产品、`parseBound`/`parseTableReference` 扩产品、途中撤权/到期复验与缓存旧范围释放、只读账号/超时/行数字节上限/取消/日志脱敏、通配符执行前展开等仍待）。

## 7. #18：多来源合并的历史数据处置

### 已有

Supply 来源账本、独立来源版本、撤销墓碑、兼容来源并集、协调器/读取快照、v2 RPC 和身份门禁已有；ADMIN 永久关闭及 API 退役引用也已补。主清单早期“RPC 未接/ADMIN 关闭未做”不应再作为当前待开发项。

### 未完成

- [x] **Supply 只读 dry-run 分类入口。** `GET /supply/app/api-auth/review-page` 按租户、AGGREGATE/SOURCE/OPERATION 独立游标返回脱敏本页计数和核查编号；覆盖旧 ADMIN、缺/坏冻结证据、孤立/重复身份、不兼容来源、软删除主体、旧 UNKNOWN、`LEGACY_REVIEW_REQUIRED`，不写库、不调用远端、不自动批准。
- [x] **Supply 只读核查页面与固定遍历上界。** 应用列表接入租户内全应用的三类独立核查；首页同一短快照捕获 `throughId`，续页原样回传，限制较大新 ID 延长扫描。校验大 ID、空页、上下界、权限组合及身份切换后的迟到响应。仅本页计数，不自动全扫、不宣称完整历史快照，不提供处置按钮；旧后端缺上界提示升级。详见[最新记录](../reviews/2026-09-27-sharing-review-scan-and-current-state.md)。
- [x] **单次原始证据关联。** `GET /asset/market/apply/delivery-evidence` 只读校验冻结身份/批准引用/租户摘要与原回执，前后短快照及二次 Guard 丢弃状态变化/撤权期间的旧响应；不输出字段名、载荷、hash，不修改授权，不重发。42 项新增真实 PG 测试通过，详见[本批记录](../reviews/2026-09-27-sharing-original-evidence.md)。
- [x] **新批准的原范围凭证。** `checkApply → enqueueApprovedGrant` 在同一事务写入 `GRANT_APPROVAL_EVIDENCE`，审批凭证失败必须回滚；恢复只比较原批准，不产生新批准。复用 Supply 规范编码，固定摘要用交付版本，不因正常暂停/恢复误判范围变更；按申请查凭证，避免修改 revision 或清空应用/API 绕过。新增 0067，新增 34 项实库测试；仅覆盖有新凭证的 API 申请，不外推历史批准或本地预览。
- [x] **正式凭证接入核查链。** 只读快照、写审计前锁内复验和页面分别展示批准匹配/缺失/冲突；保存原凭证身份/摘要与当时核对结果，同请求重试及历史查询不随后来批准改写。旧/未知字段为 UNVERIFIABLE，不将查询失败伪报 MISSING。详见[本批记录](../reviews/2026-09-27-sharing-approval-review-and-status.md)。
- [x] **跨服务完整来源证明（清点工具与处置链）。** 2026-09-29 补齐三类账本单一 REPEATABLE_READ 只读快照的全量分类计数（`GET /supply/app/api-auth/review-inventory`，截断显式标记），处置/批量/Supply 孤立旧 ADMIN 结论均已接线；见[本轮记录](../reviews/2026-09-29-sharing-disposal-completion.md)。旧历史批准原件核验与真实数据清点落地仍需授权管理员在部署环境执行，不在代码层补造。
- [x] **合法大范围清点与核查异常日志保护。** 2026-09-28 修复 64 KiB 截断造成的假无效，增加同页快照内限量逐条读取；冻结范围/原回执解析失败不记录正文，不将读库失败误报损坏。5 类 115 项通过（真实 PG 类 72），见[专项记录](../reviews/2026-09-28-sharing-review-large-scope.md)。仅证明结构可核查，不证明原批准或迁移资格。
- [x] **核查权限查询后的快照与身份复验。** 2026-09-28 修复 Guard RPC 等待期间转交/删除/绑定或证据变化后仍返回旧结果的窗口；历史/重复请求阻断跨身份拼接，保留原审计、只读和不重投边界。6 类 272 项通过（真实 PG 类 150），见[专项记录](../reviews/2026-09-28-sharing-review-authorization-race.md)。这不是历史来源处置或跨服务一致性证明。
- [x] **有限核查决定与持久审计。** 新增 POST `delivery-review` / GET `delivery-reviews`，仅为 REVIEW_REQUIRED 或 MANUAL/UNKNOWN 原操作记录 KEEP_PENDING / REAPPLY_REQUIRED。服务端生成原身份/来源版本/证据摘要，记录 actor、tenant、部门、原因、依据及相同前后状态；重取证、锁内复验、唯一请求幂等与授权历史读取已实测。使用既有领域审计表，新增迁移 0066；24 项新增真实 PG 测试通过。没有操作账本的孤立旧 ADMIN 由 2026-09-29 的 Supply 处置结论入口覆盖。
- [x] **历史交付实际处置（撤销 + 有条件确认）。** 2026-09-28 按用户决定实现 REVOKE 与 CONFIRM_ORIGINAL 两种处置，锁内复验、审计同事务、请求幂等，新增 0069；见[专项记录](../reviews/2026-09-28-sharing-delivery-disposal.md)。旧协议与缺批准凭证不确认。
- [x] **实际处置的审计闭环（绑定核查决定）。** 2026-09-29 处置必填 `basisReviewRequestId` 绑定核查决定：服务端复验同一原操作、未删除，并逐项比对决定证据与当前证据的 14 个快照字段，不一致先重新记录决定；处置证据记录决定编号/结论/记录行号/当时证据摘要。见[本轮记录](../reviews/2026-09-29-sharing-disposal-completion.md)。
- [x] **批量处置。** 2026-09-29 新增 `POST /asset/market/apply/delivery-disposal-batch`：每批 ≤20、逐条独立短事务与独立审计、逐条返回结果、部分成功不整体回滚、同批禁止重复原操作。前端页面多选入口未做，API 客户端与契约测试已交付。
- [x] **Supply 孤立旧 ADMIN 处置结论。** 2026-09-29 新增 `POST /supply/app/api-auth/legacy-admin-disposal`（继续隔离/确认已撤销，只记录不动授权）与历史读取，新增 0070；真实撤销仍走既有 `/api-auth/revoke` 终态链路。
- [x] **旧 ADMIN 代码边界与本地回归。** protocol=0 与市场/v2 隔离、不能覆盖市场聚合、永久关闭只释放自身引用、显式启用不恢复撤销来源均由真实 PG 回归确认。新增管理端初读/末读控制快照和合同一致性，历史 v2 来源变化不改变独立 ADMIN，不伪造冻结字段或调用个人主体。LegacyBoundary 39 项、AdminScope 105 项通过；部署与历史数据处置仍按下列独立待办验收，不外推多节点。见[本轮记录](../reviews/2026-09-27-sharing-legacy-and-detail-consistency.md)。
- [x] **部署切换规程。** 2026-09-29 落文档：旧节点停止、只读核查与完整清点、记录决定与实际处置、新协议启用与回退限制，见[部署切换与回退规程](../operations/sharing-disposal-deployment-switch.md)。实际切换执行仍属部署验收。
- [ ] **真实验证。** A/B 来源分别开通、更新、暂停、恢复、撤销，期限空档/交叠，不兼容合同/用途/绑定，操作重放和乱序回执，跨租户和跨节点。代码与真实 PG 单库验证已完成，完整多服务/浏览器场景保留为上线验收，不重新打开已结项项。

**完成条件：**每个历史待核查来源有明确处置结果或仍受控保留；A 撤销只去掉 A 的有效贡献，B 不受误伤；旧 GRANT 不复活墓碑。代码路径（核查决定、处置绑定、批量处置、Supply 处置结论、只读快照清点）已具备并有真实 PG 单库回归；真实多服务 A/B 来源验收与部署切换执行按[部署切换与回退规程](../operations/sharing-disposal-deployment-switch.md)保留为上线事项，不以单测数量折算完成。

入口：Supply `service/app/grant/SupplyGrantCoordinator`、`SupplyGrantReadService`、`SupplyGrantPolicy`、`SupplyGrantSourceApiImpl`、`SupplyGrantReconcileJob`，以及 Asset 来源交付账本。

## 8. #19：撤销重算、人工核查和全端状态一致性

### 已有

Asset 已取消“有其他来源就跳过撤权”的旧短路；冻结命令、先查原回执、租约/版本回写、逐租户过期作业已接线。`MANUAL/UNKNOWN` 原身份只读复查及审计已有后端接口；STALE 不再等同 APPLIED，确认 REVOKE 不等同已开通。资源/申请、需求验收及看板已有读取账本的改动。

### 未完成

- [x] **原操作人工复查页面。** “服务审批 → 交付核查”已接入 `/asset/market/apply/delivery-operation` 与 `/asset/market/apply/recheck-delivery`，显示原身份/协议/当前状态/原因及权限要求；只允许 v2 MANUAL/UNKNOWN，原因必填、校验所选申请与原操作号，租户/权限/用户/组件变化丢弃旧响应，不展示冻结载荷，不隐式重发。31 项市场测试通过；真实浏览器与服务部署仍待验收。
- [x] **只读原证据页面。** “交付核查 → 核对原始证据（只读）”已接线，包括 REVIEW_REQUIRED/旧协议分类；核对申请/操作/来源版本，租户、用户、权限、刷新、关闭、卸载均失效旧结果。不解锁历史批准/重发，市场域最新 44 项前端测试通过。
- [x] **核查结论与历史页面。** “交付核查 → 核查结论与历史（不改授权）”支持两类待办决定、必填原因/依据、分页历史与当前身份核对。失败后冻结原请求编号/内容供显式重试，提交期间禁用输入；切换租户/权限/用户、关闭或卸载丢弃旧结果。前端市场域最新 66 项通过；真实浏览器仍待。
- [x] **历史处置。** 2026-09-29 处置结论扩展到只读核查页除"继续隔离/保留墓碑"外的全部待核查分类（AGGREGATE/SOURCE/OPERATION 三类账本），只记录继续待核查/需撤销后重申请、不动授权，新增 0071 与 `POST /supply/app/api-auth/review-disposition`；Asset 侧 REVIEW_REQUIRED 原操作的撤销/确认在 #18 已闭环。见[本轮记录](../reviews/2026-09-29-sharing-redelivery-and-disposition.md)。旧批准原件核验与真实迁移仍由授权管理员在部署环境执行。
- [x] **NOT_FOUND 政策（用户确认：受控重投，仅撤销/暂停）。** 2026-09-29 新增 `POST /asset/market/apply/redeliver-delivery`：独立权限 `asset:market:redeliver`（0073）、必绑核查决定、仅限冻结命令完整匹配的 MANUAL/UNKNOWN 撤销/暂停原操作、按原 operationId 重发一次、意图审计唯一请求幂等（0072）；GRANT 一律拒绝；未知结果保持 MANUAL/UNKNOWN。自动协调器"权威查无后按原身份重发"路径保持不变，两者不再混为一谈。
- [x] **人工原回执对账写回授权竞争。** 2026-09-28 完成入口身份固定、外层事务拒绝、查询前后授权/快照复验、短事务申请/资产/操作锁与账本/审计同事务；变化拒绝，不借旧权限写回。9 类 410 项通过（真实 PG 类 195），见[专项记录](../reviews/2026-09-28-sharing-manual-recheck-authorization.md)。不自动重投、不恢复批准；不外推所有 GET 读入口或跨服务权限的全局线性一致性。
- [x] **交付详情与人工对账回显授权竞争。** 2026-09-28 共用双快照读取，权限查询期间本地归属/绑定/原操作和身份变化拒绝；补齐原详情字段，保留旧协议状态可读及超限载荷保护。11 类 484 项通过（真实 PG 类 231），见[专项记录](../reviews/2026-09-28-sharing-delivery-detail-authorization.md)。回显拒绝不等于已提交对账回滚，不重发命令；未外推其他 GET 或真实多服务一致性。
- [x] **Supply 授权列表与来源贡献。** 列表复用来源账本权威读取，不再用旧 grant_status 或本机时间推断 v2 可用；来源贡献绑定同一求值快照/版本，禁用、待核查或主体失效时不冒报贡献，保留历史字段和期限。前端状态标签同步为“不可用”，不把旧 ADMIN 撤销与市场来源混同。
- [x] **管理端返回前的 v2 状态复验。** 隔离调用方旧事务，来源展示读取后复用 `assertStillCurrent`，主体 RPC 后再次检查本地状态、版本、数据库时间和租户；读取期间撤销/禁用/到期不返回旧 ACTIVE 或字段。九类回归 249 项通过，真实 PG 类 219；这不是全列表统一快照或跨服务线性一致性，旧 ADMIN 沿用隔离语义。
- [x] **管理端旧 ADMIN 状态复验与网关合同一致。** 独立旧授权新增返回前短快照，对撤销、禁用、期限、主体/对象/版本/合同变化拒绝旧结果；已不匹配合同显示待复核。抽取并复用网关原合同规则，保留空合同兼容，未扩大授权。两个真实 PG 反例先失败后通过，扩大回归 11 类 311 项（真实 PG 类 253）全部通过，见[本轮记录](../reviews/2026-09-27-sharing-legacy-and-detail-consistency.md)。
- [x] **应用详情与实际范围异步一致性。** 四类读取按页面/应用/身份及最新请求隔离，刷新失败清除旧 ACTIVE/字段，首次未确认不显示“字段未受限”；来源贡献严格布尔值，来源表采用系统＋来源键复合行身份。确认后再验本行身份/版本，不借用其他范围版本；防重复提交，未知新增锁定当前表单，关闭/身份变化后不再跨页刷新或提示成功。Supply 前端 283 项通过（详情新增 168），见同一交付记录；真实浏览器仍未验收。
- [x] **全端统一状态。** 申请/需求展示已由 2026-09-28 数据库时钟轮改为复用 `MarketAccessService.isEffective`（数据库时钟、`[start, end)` 半开区间），`MarketDisplayHelper.inEffect` 已删除；本轮核对剩余页面/统计：申请列表/详情、资源/总览、技术统计均读取同一账本口径，"上线 API 数不改成可访问授权数"的说明已保留在统计口径注释。见[时钟专项](../reviews/2026-09-28-sharing-database-clock.md)。
- [x] **实际访问范围专项。** 多来源不同期限/字段、撤销一份后字段收缩、最后一份到期后拒绝访问、查询进行中收窄/到期丢弃旧结果，已由 `MarketMergedAccessPostgresTest`（27 项）与 `MarketQuerySnapshotPostgresTest`（13 项）真实 PG 覆盖；本轮回归重跑通过，未发现新缺口。
- [x] **需求验收终态竞争。** `MarketDemandServiceImpl.acceptDemand` 最终 UPDATE 同时比较 ID、原状态、原负责人和 acceptanceStatus，必须恰好更新一行；并发关闭/取消/驳回、转交、软删除或另一次验收不能复活终态或冒充成功。真实 PG 双连接反例先复现再修复；已验收后关闭再重试保持终态。
- [x] **需求验收的匹配关联保护。** 2026-09-28 补齐已接受匹配、申请/资产/用户关联校验及匹配行锁，锁等待后复验、持有至提交；与既有申请锁/24 字段授权比较、需求 CAS/数据库时间配合。19 个反例转绿，8 类 405 项（真实 PG 类 120）通过，见[专项记录](../reviews/2026-09-28-sharing-demand-match-acceptance.md)。允许同用户同资产跨需求复用，不改已完成历史验收，不代表当前远端聚合可访问。
- [x] **匹配确认与草稿原子性。** 2026-09-28 补齐原匹配关联条件、申请回填行数和三模式需求 CAS；并发终态/转交/申请依据变化/删除不再留下已确认匹配或孤立草稿，过程记录同事务回滚，通知只在提交后发送。39 个反例转绿，9 类 522 项（真实 PG 类 185）通过，含锁等待、真实申请/过程写入、跨租户和并发重复确认，见[专项记录](../reviews/2026-09-28-sharing-match-confirmation-atomicity.md)。不改变旧申请复用、正式批准或远端交付策略。
- [x] **需求编辑/删除/发布/关闭的状态竞争。** 2026-09-28 四类单 SQL 写入精确比较原状态、归属与验收依据，编辑不回写旧系统字段，删除保留框架逻辑删除，行数非 1 拒绝。53 个反例转绿，11 类 813 项（真实 PG 类 292）通过，含真实发布与旧编辑/删除竞争、行锁等待和数据库写失败，见[专项记录](../reviews/2026-09-28-sharing-demand-mutation-concurrency.md)。不代替全部表单内容的乐观版本控制。
- [x] **响应/新增匹配的终态竞争。** 2026-09-28 两个入口补 INSERT 行数、10 秒事务和需求快照 CAS；关闭/转交/验收依据或关键内容变化/删除后回滚子记录，提交失败不发通知，原 MATCHING/MATCHED/APPLYING 不倒退。34 个反例转绿，13 类 980 项（真实 PG 类 360）通过，含真实关闭、行锁等待、延迟提交失败和合法双贡献，见[专项记录](../reviews/2026-09-28-sharing-demand-contribution-atomicity.md)。同需求同资产并发去重、全部内容版本及历史清理未据此宣称完成。
- [x] **同需求同资产并发匹配唯一性。** 2026-09-28 新增0068部分唯一索引和定向原子插入，保留租户隔离、所有未删除状态查重、序列/审计填充与提交后通知；先写者回滚时等待者可成功，其他约束失败不伪装重复。14 类1024项（真实PG类400）通过，包含迁移首执行/重放、历史重复不改写、错误同名对象和锁超时，见[专项记录](../reviews/2026-09-28-sharing-demand-match-uniqueness.md)。代码子项完成，目标库需按先0068后应用发布；历史处置、完整新库基线和资产变更竞争不据此结项。
- [x] **新增匹配期间资产变更竞争。** 2026-09-28 插入后按租户/ID/未删除条件取得资产共享锁，复核状态及23字段，保护到需求CAS与事务结束；变化回滚，不发送成功通知。14类1210项（真实PG类443）通过，覆盖锁等待后重读、资产写者等待、不同需求同资产正常并发、需求关闭回滚、通知前释放锁、显示字段不误拒绝，见[专项记录](../reviews/2026-09-28-sharing-match-asset-consistency.md)。不修改稍后确认/申请入口，不把跨请求历史或ABA称为已解决；本轮无新迁移。
- [x] **接受匹配/生成申请期间资产变更竞争。** 2026-09-28 两种接受模式共用初读准入和23字段锁内复验，确认/草稿/过程/需求一起回滚；拒绝无效资产仍可完成，异常空关联使用精确条件且不可接受。最终14类1403项（真实PG类542）通过，含真实锁等待、申请复用、提交失败、正常共享锁并行、NULL双向换绑及提交后通知；见[专项记录](../reviews/2026-09-28-sharing-confirmation-asset-consistency.md)。不证明已有申请自身并发安全或跨请求不可变历史，本轮无新迁移。
- [x] **已有申请复用期间的失效/换绑与提交竞争。** 2026-09-28 候选单次读取，复用所属申请锁及当前资格复验；失效不改选/新建，锁持有到外层事务结束，返回当前合法行而不改写原申请。14类1502项（真实PG类574）通过，覆盖真实锁等待、申请写者等到提交、需求/资产/提交失败释放锁、两个需求串行复用同一申请；见[专项记录](../reviews/2026-09-28-sharing-demand-apply-reuse.md)。原状态准入及跨需求政策保留，无新迁移；无候选时首次创建竞争和写入行数仍单独核查。
- [x] **需求确认中新建草稿的并发冲突与写入完整性。** 2026-09-28 定向处理0050在途唯一冲突，非目标约束异常不误报重复；草稿行数/生成主键和过程行数异常整体回滚，不发送提交后通知。14类1557项（真实PG类598）通过，覆盖实际唯一索引等待/提交/回滚、触发器零写入、审计/租户、超JS安全范围主键和缺索引安全失败；见[专项记录](../reviews/2026-09-28-sharing-demand-draft-creation.md)。仅收口需求草稿分支，无新迁移。
- [x] **普通申请创建的冲突分类及完整写入。** 2026-09-28 `createApply` 共用定向INSERT与零行复读，其他约束失败不误报重复；快照/目录版本/时限首次一起写入，过程非一行回滚，显式JSONB绑定，生成主键不依赖客户端ID。15类1677项（真实PG类628）通过，覆盖零写入、并发/唯一等待、真实COMMIT失败、字段/租户/审计填充及JSONB；见[专项记录](../reviews/2026-09-28-sharing-application-creation.md)。无新迁移。
- [x] **独立草稿提交的过程完整性与JSONB更新。** 2026-09-28 `submitApply` 保留原CAS，增加可信租户前置、参数化JSONB转换和过程恰好一行校验；真实PG证明零过程/约束/COMMIT失败及外层回滚无半提交或通知，双提交只写一份。7类696项（真实PG类317）通过，见[专项记录](../reviews/2026-09-28-sharing-draft-submission.md)。无新迁移，不改变工作日期限、审批策略或既有成功调用顺序。
- [x] **补正重提的JSONB绑定与过程写入完整性。** 2026-09-28 `resubmitApply` 增加可信租户前置、参数化JSONB及过程恰好一行门禁，旧版本CAS与新快照版本保持一致，失败整行/过程回滚、不自动重试。9类802项（真实PG类346）通过，覆盖过程零写入、约束/COMMIT失败、外层回滚、并发重提、版本/租户/归属变化及大ID；见[专项记录](../reviews/2026-09-28-sharing-supplement-resubmission.md)。无新迁移，不新增通知、批准或交付行为。
- [x] **要求补正的过程完整性与失败回滚。** 2026-09-28 `requestSupplement` 补齐可信租户前置、过程非一行抛冲突；真实PG证明状态/说明与过程原子提交，原快照/版本/时限不变，重复/并发补正及越权拒绝。7类505项（真实PG类113）通过，覆盖实际对象Guard及三类提供方路径、过程/COMMIT失败和跨租户负例；见[专项记录](../reviews/2026-09-28-sharing-supplement-request.md)。无新迁移，不新增通知/交付或发布资格限制。
- [x] **审批通过/驳回的过程完整性与本轮意见通知。** 2026-09-28 `checkApply` 过程非一行回滚，后续协作者使用本次已写入审核信息；原通过冻结校验、驳回历史证据准入保留。真实PG验证批准凭证/来源/交付异常及提交失败一起回滚、成功提交后才领取/通知、并发只一个结果；10类753项（真实PG类133）通过，见[专项记录](../reviews/2026-09-28-sharing-application-checking.md)。同步旧冻结范围测试夹具，无新迁移。
- [x] **申请人撤销的过程完整性与失败回滚。** 2026-09-28 `cancelApply` 过程必须恰好一行，失败回滚；可信租户前置、NULL状态使用原业务拒绝，原草稿/待审及申请人资格、版本CAS保留。真实PG验证零写入、COMMIT失败、重复/并发、租户与登录Guard，以及撤销对提交/审批双向竞争。8类539项（真实PG类145）通过，见[专项记录](../reviews/2026-09-28-sharing-application-cancellation.md)。无新迁移，不等于已授权资源的远端撤权。
- [x] **开通/停用授权的过程完整性与失败回滚。** 2026-09-28 `authApply` 过程必须恰好一行，失败不进入来源/交付/通知；可信租户前置、NULL状态业务拒绝，原源版本CAS/幂等/冻结范围/SUSPEND语义保留。10类609项（真实PG类162）通过，覆盖真实过程/来源/交付/COMMIT故障、提交后发送、双命令竞争、版本溢出及无API本地来源；见[专项记录](../reviews/2026-09-28-sharing-application-authorization.md)。无新迁移，不新增或改写原正式批准凭证，不代表远端已生效。
- [x] **收回授权的过程完整性与终态交付回滚。** 2026-09-28 `revokeApply` 过程非一行整体回滚、可信租户前置，失败不继续来源/REVOKE/通知；原CAS及提供方规则保留，不重造批准。10类592项（真实PG类161）通过，真实PG覆盖来源/交付/COMMIT故障五表回滚、并发只留胜方原因与版本、重复/再开通拒绝、坏快照/到期兼容和无API来源；见[专项记录](../reviews/2026-09-28-sharing-application-revocation.md)。无新迁移，不等同远端撤权已执行。
- [x] **自动到期的过程完整性与批次完成计数。** 2026-09-28 `expireOne` 拒绝异常状态行数和非一行过程写入，逐条回滚；竞争零行仍丢弃，提交成功后才计完成，保留失败继续与批末汇总。12类645项（真实PG类201）通过，覆盖过程零行故障恢复、五表回滚、延迟COMMIT失败、双扫描只提交一次及100+1批次；见[专项记录](../reviews/2026-09-28-sharing-application-expiration.md)。无新迁移，不等同远端Supply已撤权或未知提交结果已全部对账。
- [x] **续期申请写入完整性与JSONB快照。** 2026-09-28 `createRenewal` 在保存点内要求INSERT一行/有效主键，RENEW过程非一行整体回滚，失败不返回成功ID；新Mapper显式参数化JSONB，保留原操作幂等、父锁和保存点复读，不原地延期/直接授予。8类474项（真实PG类111）通过，覆盖真实零行、主键回填异常回滚、COMMIT失败可重试、完整继承字段和原申请不变；原34项PG并发回归升级为JSONB继续通过，见[专项记录](../reviews/2026-09-28-sharing-renewal-write-integrity.md)。无新迁移，不等同父子级联政策或普通审计强持久性已完成。
- [x] **暂停/恢复授权的过程完整性。** 2026-09-28 两入口可信租户前置、NULL状态业务拒绝；过程非一行整体回滚、不推进下游对象或交付，保留重复目标拒绝、双开关、版本CAS、到期和恢复范围校验。10类640项（真实PG类213）通过，覆盖五表回滚、提交失败、双操作者只提交一次、外层回滚及无API路径；见[专项记录](../reviews/2026-09-28-sharing-application-suspension.md)。无新迁移，不等同远端Supply已暂停/恢复或普通审计强持久性完成。
- [x] **交付内部写入完整性。** 2026-09-28 `persistGrantSource` / `enqueue` 五处写入要求恰好一行，冻结操作须回填有效正数主键；失败不继续回填/领取，调用方真实事务整体回滚。20个真实PG先红反例转绿，12类862项（真实PG类350）通过，覆盖零行回滚及显式重试、真实INSERT后主键映射故障、延迟COMMIT失败、提交可见性和无API路径；见[专项记录](../reviews/2026-09-28-sharing-delivery-write-integrity.md)。保留来源版本、冻结批准、协议与提交后领取，不改未知结果重投或父子政策，无新迁移。
- [x] **普通审计强持久性、失败语义与交付意图主键。** 2026-09-28 普通审计与业务同事务、可信租户/操作人前置、恰好一行，失败原样回滚且不发提交后通知；评价与审计同短事务、工单提交后发起；使用日志以保存点隔离。探针证明旧代码审计失败时静默回滚却返回成功并发通知；另修复现网 INPUT 主键模式下 `insertFrozen` NULL 主键。73项专项、完整Asset 76类3703项（真实PG 1506）通过（2个既有过期迁移用例报错与本轮无关），见[专项记录](../reviews/2026-09-28-sharing-audit-durability.md)。无新迁移。
- [x] **提供方决定与资产归属读取后的串行化。** 2026-09-28 六个提供方入口在申请 CAS 后锁内复验资产归属，开通类再验冻结绑定；共享锁持有到提交，归属修改等待，同资产其他决定并行，停止类不因换绑受阻。49项专项（35项先红）及完整Asset 77类3752项通过，见[专项记录](../reviews/2026-09-28-sharing-provider-decision-serialization.md)。无新迁移。
- [x] **其余并发与审计边界（用户决定：补普通审计，其余保持现状）。** 2026-09-29 撤销/开通/收回/自动到期四个入口补写普通业务审计事件（APPLY_CANCEL/APPLY_AUTH/APPLY_REVOKE/APPLY_EXPIRE，与状态变更同事务、失败整体回滚；到期作业复用申请人作为操作者，`MarketAuditRecorder` 增加显式操作者重载）。保持现状并记录的边界：System 角色撤销窗口仍由锁内复验+冻结比对覆盖、不做提交后复核；校核评价幂等唯一约束不建（历史重复需先人工核查）；资产下架期间仍可提交（换绑由批准时冻结比对安全拒绝）。
- [x] **申请与资源的本地交付呈现规则。** 2026-09-28 统一在既有 MarketDeliveryService：SUCCESS 必须配 CONFIRMED，取消及未知组合不再等同无需交付；申请技术状态与结果读取同一账本，保留原投递意图。资源 authorized/usable 绑定同一份有效申请，修复“未授权却可使用”。四个反例先红后绿，6 类 137 项含直接消费者回归通过，见[专项记录](../reviews/2026-09-28-sharing-delivery-presentation.md)。这不是当前 Supply 聚合或整页数据库快照证明。
- [x] **市场资源/总览页面异步与展示边界。** 两页按租户/访问租户/用户/权限/生命周期失效旧响应，刷新与失败清除旧结果；只对已核对身份的库表检查预览权限，检查失败保留目录、关闭预览。资源不把登记交付称为聚合可访问；概览缺失计数显示未知并解释审批授权口径。11 文件 177 项通过（新增两页 64 项），真实浏览器仍未验收，详见同一专项记录。
- [x] **交付意图与账本口径的其余入口。** 2026-09-28 暂停/终态撤销动作呈现已区分"撤销受理（PENDING 意图）"与"暂停/撤销已确认"（`MarketDeliveryOperationRespVO.action` + 前端统一映射）；本轮核对申请/资源/总览/技术统计均不再以旧 PENDING 意图推断结果，剩余口径差异已写入注释与统计说明。
- [ ] **真实 Supply/Asset 联调。** 确认调度中心 `marketApplyExpireJob` 的 Cron、逐租户运行、mTLS、超时、响应丢失、接管和乱序回执；本地事务不能覆盖远端副作用。保留为上线验收，按[部署切换与回退规程](../operations/sharing-disposal-deployment-switch.md)执行。
- [x] **到期/补偿批内故障隔离。** 2026-09-28 修复单条失败中断本批、到期阶段失败跳过补偿阶段、租约丢失误计成功；独立事务、原租约与原回执恢复保持，批末向调度器报告失败。真实 PG 覆盖首/中/末条、全部失败、故障恢复不重复入队及回写失败后接管不重发。详见[本轮验证与运维边界](../reviews/2026-09-28-sharing-maintenance-failure-isolation.md)。保留 100/50 上限，不宣称大规模永久失败堆积的公平扫描或生产调度验收完成。
- [x] **实际访问范围专项。** 多来源不同期限/字段、撤销一份后字段收缩、最后一份到期后拒绝访问、查询进行中收窄/到期丢弃旧结果，已由 `MarketMergedAccessPostgresTest`（27 项）与 `MarketQuerySnapshotPostgresTest`（13 项）真实 PG 覆盖；本轮回归重跑通过，未发现新缺口。

**完成条件：**后端真实可访问字段、来源账本、操作回执和所有相关页面一致；未知不伪装成成功，复查不隐式重发，终态不被旧验收请求复活。代码侧已满足（真实 PG 双连接竞争、审计失败回滚、跨租户负例均有覆盖）；真实 Supply/Asset 多服务联调与浏览器验收按上线事项保留。

入口：Asset `MarketDeliveryService`、`MarketDeliveryCoordinator`、`MarketDeliveryOperationMapper`、`MarketDemandServiceImpl`、`MarketApplyExpireJob`；Supply 原操作回执查询。

## 9. #33：已完成（2026-09-27 用户确认结项）

以下真实多节点与主要页面验收内容保留为上线验收参考，不代表已经全部运行通过，也不再计入本次未完成项。

### 已有证据不能外推的边界

已有真实 PostgreSQL、独立连接/事务、双 JVM 协调器和实际子进程终止测试；部分 TLS 链路也有专测。但尚未完成“两套完整业务应用 + 真实引擎 + Redis + 网关/权限 + 调度中心”的整链验收，局部模拟引擎不能替代它。

### 环境与实施前置

- [ ] 给出隔离部署拓扑：相关业务应用至少 A/B 两实例、隔离平台 PG、Redis、网关、可信服务身份、XXL-Job 和选定真实目标引擎/数据库。
- [ ] 不连接现有业务库，不借用生产账号。测试凭证仅用于一次性环境且不得写进交接文档或源码。
- [ ] 数据库产品串行验证：完成并关闭上一产品后再开下一产品；应用、Redis、网关等必要依赖按测试拓扑保留，不同时起全部 27 种库。
- [ ] 核对 SeaTunnel 新原文配置的受托管连接绑定门禁。当前安全拒绝不能为“让测试跑通”直接解除；已知远端作业查询/停止与新提交分别验收。

### 必测场景

| 场景 | 通过标准 |
| --- | --- |
| A/B 同时扫描/受理/领取 | 同一调度批次一个有效执行身份，无重复外部写入 |
| A 执行，B 取消 | 停止意图可追踪；真实确认退出才终止，未确认保留 UNKNOWN |
| 受理前后、领取前后、外部提交后 kill 节点 | 未发送可恢复；可能已写入先查证，不能盲重跑 |
| 旧节点恢复、过期租约、迟到心跳/回调 | 主记录、节点、摘要、水位不被旧身份覆盖 |
| 远端成功但回执丢失 | 以原 operationId 对账，不生成新身份重复副作用 |
| 同 nonce 同时打到两节点、Redis 故障 | 最多一次接受；存储故障不降级成本机放行 |
| 伪造内部身份头、直连 RPC、非法服务证书 | 拒绝；合法服务传播仍满足租户与对象权限 |
| 多来源批准/暂停/续期/撤销/到期乱序 | 不覆盖独立来源、不复活墓碑，最终字段范围正确 |
| 查询过程中撤权/字段收窄/到期 | 返回前复验，不能释放旧数据或旧缓存 |
| 同次整改多轮复检、旧结果后到 | 新轮独立，旧 PASS 不覆盖当前轮；缺到数证明仍等待 |
| 恢复长任务、重启、部分写入和响应丢失 | 状态可查询/核查，未知不自动覆盖或重跑 |
| 血缘远端不可用、发送节点退出、死信重放 | 搬数结果不被回滚，补发幂等且权限有效 |
| 大积压、慢目标、监控阻塞 | 续租/取消不饿死，容量与告警目标有实测数据 |
| ER、探查、大 ID、工单/共享主要页面 | 真实路由/请求/权限/租户正确，旧响应不覆盖新页面 |

每次保存版本及配置摘要、节点身份、脱敏操作 ID、时间线、数据库最终状态、外部副作用计数、执行/跳过数和清理结果。失败/缺环境写“待验收”，不按单测数量折算通过。

## 10. #11：全数据库复检可比性与覆盖证明

### 10.1 共性未完成工作

- [ ] 覆盖 `DatabaseFactory` 注册的全部 27 个非 UNKNOWN 产品。产品原有 CRUD/连接能力不等于已支持本项新凭证。
- [ ] 每个产品建立真实源实例、物理关系及恢复代际证明；同名表重建、恢复/克隆、路由切换不能复用旧凭证。
- [ ] 计数、规则结果、记录键和机构覆盖必须来自同次有证据的读取；不能拼接不同读取的结果，不能把数据源配置修订等同数据版本。
- [ ] 除已证明的 NOT_NULL/有限键路径，继续补规则类型、原生数据类型、表形态、机构组合和完整治理调用；不把组件扫描单测当整改自动验收。
- [ ] 明确超过键数量、字节长度、机构数量、复合键列数的处理；不能静默截断后 PASS。当前部分路径受 100000 键、256 UTF-8 字节、2–4 列复合键、1024 机构等预算约束，逐实现核对。
- [ ] 对合理增删键、自定义 SQL、无键/多字段机构及动态类型制定有证明的可比性规则；不能仅为减少不可比放松条件。
- [ ] 历史无关系凭证/旧 v1 PASS 不自动补造；需要旧 PASS/历史关闭工单的只读审计、显式退回/重检和人工处置方案。
- [ ] 全执行/判定节点统一升级；补混合版本拒绝、真实路由变化、规模边界和浏览器原因说明。

### 10.2 27 产品现状与缺口

以下源于[逐产品检查点](../reviews/2026-09-24-quality-database-compatibility.md)及其后续记录。2026-09-29 的 DB2 行仍是普通权限启动失败，见[DB2 再试记录](../reviews/2026-09-29-quality-db2-environment.md)；MongoDB 行按当次实库更新，见[MongoDB 记录](../reviews/2026-09-29-quality-mongodb-key-coverage.md)；InfluxDB 行按当次实库更新，见[InfluxDB 记录](../reviews/2026-09-29-quality-influxdb-key-coverage.md)；SQLite 行按当次实库更新，见[SQLite 记录](../reviews/2026-09-29-quality-sqlite-key-coverage.md)；Hive 行按当次实库更新，见[Hive 记录](../reviews/2026-09-29-quality-hive-key-coverage.md)；SQL Server 2022 行按当次实库更新，见[SQL Server 2022 记录](../reviews/2026-09-29-quality-sqlserver-key-coverage.md)；Oracle 行按当次实库更新，见[Oracle 记录](../reviews/2026-09-29-quality-oracle-key-coverage.md)；Elasticsearch 行按当次实库更新，见[Elasticsearch 记录](../reviews/2026-09-29-quality-elasticsearch-key-coverage.md)；优炫行按当次实库更新，见[优炫记录](../reviews/2026-09-29-quality-uxdb-key-coverage.md)；SQL Server 2000 行按当次实库更新，见[SQL Server 2000 记录](../reviews/2026-09-29-quality-sqlserver2000-key-coverage.md)；GBase 8a 行按当次实库更新，见[GBase 8a 记录](../reviews/2026-09-29-quality-gbase8a-key-coverage.md)；神通行按当次实库更新，见[神通记录](../reviews/2026-09-29-quality-oscar-key-coverage.md)；金仓行见[金仓记录](../reviews/2026-09-29-quality-kingbase-key-coverage.md)；Sybase 行见[Sybase 记录](../reviews/2026-09-29-quality-sybase-key-coverage.md)；达梦行见[达梦记录](../reviews/2026-09-29-quality-dm-key-coverage.md)；瀚高行见[瀚高记录](../reviews/2026-09-29-quality-highgo-key-coverage.md)；Greenplum 行见[Greenplum 记录](../reviews/2026-09-29-quality-greenplum-key-coverage.md)。其余行本次未重跑。表内“待”只指 #11 新能力无闭环证据，**不是说 DB Switch 从未实现过该数据库**。#17 除已单独记录的投影路径外，完整绑定/授权仍需按产品核对。

| 产品 | #11 已有的有限证据 | 仍需实现/验证 |
| --- | --- | --- |
| MYSQL | 8.4 普通 InnoDB 身份；有符号整数/有界文本单键、2–4 列键、整数/文本机构 | 其他类型/规则/表形态、范围变动、完整集群及 #17 |
| MARIADB | 11.4.13 受管代际；整数键/复合键/机构和 NOT_NULL ALL/ORG | 文本/其他规则；DBA 初始化、恢复后轮换及全节点排空约束落实，不声称自动恢复检测 |
| ORACLE | Free 23.26.3.0.0 普通堆表身份 `ORACLE_23_26_3_0_0_HEAP_V1`；有符号 NUMBER(p,0)/Int64 单键、2–4 列复合键、整数机构和 NOT_NULL ALL/ORG 实库通过 | 视图/全局临时/索引组织/分区/外部表/簇表/物化视图/VPD、空表再 TRUNCATE 不改变 DATA_OBJECT_ID、MOVE 改变代际、非整数 NUMBER 与文本、其他规则、物理恢复/PDB 切换/RAC；不能套达梦、金仓或神通 |
| SQLSERVER2000 | 8.00.760 SP3 已实测：DROP/CREATE 改变 sysobjects.id，TRUNCATE 不改变对象 ID，页指针随插入分配并在截断后复用，因此未发凭证 | 找到每次截断都改变、写入不改变的原生代际后，才能做整数键、机构和 NOT_NULL；不能套 SQL Server 2022。模块 mssql-jdbc 13.4 仍拒绝 major version 8 |
| SQLSERVER | 2022 16.0.4295.3 RTM-CU27 已实测：DROP/CREATE 改变 object_id，TRUNCATE 不改变 object_id、partition_id、hobt_id 或 allocation_unit_id；页指针截断后清零并在再次插入时复用，因此未发关系凭证。既有 signed 整数键、机构和 NOT_NULL 读取仍在 | 找到每次截断都改变、写入不改变且不被再次插入复用的原生代际之前，不能自动整改验收。不能套 SQL Server 2000。文本/其他规则仍待 |
| POSTGRESQL | 普通表身份；有限整数/文本/UUID/复合键及机构凭证；#12 PG16 INSERT/COPY 提交回执、完整批次范围与同规则只读快照诊断已验证 16.15 | 恢复代际/对象/字段值范围联合证明、真正副本/代理、视图/分区/RLS、真正增量等其他模式/规则/范围及历史基线；不能声称任意 SQL 可比 |
| GREENPLUM | 7.1.0 永久 heap hash 身份；signed int2/int4/int8、原生 VARCHAR/TEXT 有界 UTF-8 单键、2–4 列整数/文本混合键、整数/文本机构及 NOT_NULL ALL/ORG 实库通过；本轮新增字节投影原生 15、身份 2、治理链 26 项 | CHAR/NUMERIC/浮点/UUID 等其他类型、随机/复制/AO/列存/分区/外部/视图/临时/RLS、其他规则、物理恢复/克隆代际、真实多机与精确 parseBound；PG COPY 测试不能代替 |
| DB2 | 12.1.5.0 普通权限在 2026-09-25 与 2026-09-29 都因 SQL1042C 起不来，Q11DB 未创建，未测代际，未计产品通过 | 先有获准的独立测试库，或明确批准仅一个一次性容器使用特权，再做元数据/身份/读取/整改。不能把容器 running 当数据库可用。DDL 获取历史路径有 SYSTOOLS 写入风险 |
| DM | V8 / 0x7000d、CASE_SENSITIVE=1 普通永久行表身份；TINYINT/SMALLINT/INTEGER/BIGINT 单键、2–4 列复合键、整数机构和 NOT_NULL 实库通过 | 视图/全局临时/HUGE/分区/物化视图/加引号大小写名、外部表未测、文本和其他规则、物理恢复代际；不能套 Oracle、PostgreSQL 或瀚高 |
| SYBASE | 16.0 SP02 PL02 已实测：DROP/CREATE 改变对象 ID，TRUNCATE 没有稳定代际，因此未发凭证 | 找到每次截断都改变、写入不改变的原生代际后，才能做整数键、机构和 NOT_NULL；不能套 SQL Server |
| KINGBASE | V008R006C009B0014、oracle 模式且 enable_ci=on 的永久 heap 身份；signed int2/int4/int8 单键、2–4 列复合键、整数机构和 NOT_NULL 实库通过 | 视图/物化视图/UNLOGGED/分区/继承/非空 reloptions/规则/临时/全局临时/file_fdw 外部表/RLS、tinyint 与 numeric、文本和其他规则、enable_ci=off 与其他 DB_MODE、物理恢复代际；安全标签提供程序未加载；不能套 PostgreSQL、瀚高或达梦 |
| OSCAR | V8.0.251217 永久 heap 身份；signed int2/int4/bigint 单键、2–4 列复合键、整数机构和 NOT_NULL 实库通过 | 视图/全局临时/分区/非默认段/规则/外部表/本地临时/行策略、tinyint 与 numeric、文本和其他规则、物理恢复代际；物化视图等形态本构建未建成；不能套 Oracle、达梦、PostgreSQL 或金仓 |
| GBASE8A | 8.6.2.43-R7-free.110605 已实测：DROP/CREATE 改变 TABLE_ID，TRUNCATE 不改变 TABLE_ID，SCN、UPDATE_TIME 和 MAX_ROWID 随写入变化，因此未发凭证 | 找到每次截断都改变、写入不改变的原生代际后，才能做整数键、机构和 NOT_NULL；不能套 MySQL、Doris、OceanBase 或 TiDB。本机单节点上 FILES 与 table_distribution 为空 |
| HIVE | 4.1.0 已实测：外部 TEXTFILE purge 的目录 inode、location、createTime 和 metastore id 不随 TRUNCATE 变化；文件 inode 截断后消失、插入时新建；lastDdlTime 与行数统计随写入变化。DROP/CREATE 才改变 metastore id。因此未发关系凭证。既有 signed 整数键、机构和 NOT_NULL 读取仍在 | 找到每次截断都改变、写入不改变且空表仍能读取的原生代际之前，不能自动整改验收。ACID 在 DummyTxnManager 下建不成（10265）。文本/其他规则、分区和视图仍待。不能套其他产品 |
| SQLITE3 | 3.31.1 已实测：没有 TRUNCATE。rootpage 在清空和 DROP/CREATE 后都复用；freelist 随再次插入复用，且旁表清空也会改变它；schema_version 只随 DDL/VACUUM 变化；data_version 随其他连接的插入变化。因此未发关系凭证。动态类型和 NULL 主键反例见 2026-09-25 | 找到每次清空都改变、插入不改变且不被其他表复用的原生代际之前，不能做有界键扫描或自动整改。不能套 Hive。完整 DDL、虚拟表和搬数仍待 |
| DORIS | 4.0 internal OLAP/case 0 身份；4.0.8 实测整数键/复合键/机构链 | 文本/其他规则/表形态、恢复与集群；不推广至全部部署配置 |
| MONGODB | 7.0.43 已实测：没有 truncate。deleteMany 不改变集合 uuid 或 WiredTiger ident；count 和逻辑 size 随插入和删除变化。未落盘时 storageSize 也不随清空变化，compact 会改变非空集合的 storageSize。DROP/CREATE 才换 uuid，collMod 只改 options。因此已有集合指纹不是清空代际。账号 UUID 和授权元数据见 2026-09-24 | 找到每次清空都改变、插入不改变且不会被 collMod 或 compact 单独改掉的原生代际之前，不能做键扫描或自动整改。不能套 Elasticsearch 或 InfluxDB。驱动发布许可、副本集和分片仍待 |
| CLICKHOUSE | 25.8 Atomic/普通 MergeTree 身份；有限整数/文本键、混合键/机构和 NOT_NULL | 其他引擎/类型/规则、行策略边界、恢复和完整集群 |
| TIDB | 8.5.6/TiKV 原生身份；有限整数/文本混合键/机构与 NOT_NULL，双 SQL 节点身份测试 | 其他类型/规则、完整应用多节点；双 SQL 节点不等于业务应用验收 |
| TDENGINE | 3.3.8.8 普通表原生代际；整数键/机构和 NOT_NULL 实际链路 | 原生时间戳/文本键、超级表/子表、其他规则/真正多节点 |
| INFLUXDB3 | 3.11.5 已实测：没有 TRUNCATE，SQL UPDATE、DELETE 和 DROP TABLE 也不支持。delete table 把活表改成带时间戳的墓碑并保留旧行，再次写入复用原名；information_schema 没有表 id。WAL 序号随写入增加，catalog 日志序号删除和写入都会增加且不是 SQL 可见。parquet_files 在刚写入时为空。因此未发关系凭证。既有 signed Int64 单键、2–4 列复合键、整数机构和 NOT_NULL 组件扫描仍在 | 找到每次清空都改变、写入不改变且 SQL 可见、清空后原关系仍能读取的原生代际之前，不能自动整改验收。不能把 node-id、catalog uuid、WAL 序号或墓碑名当成代际。不能套 SQLite 或 Hive。文本、时间、tag、DDL/write/sync 和多节点仍待 |
| STARROCKS | 3.5.21 native OLAP 身份；有限整数/文本/混合键、机构及 NOT_NULL | 权限模式切换、其他类型/规则/完整集群 |
| HIGHGO | 6.0.4.2 永久 heap 身份；signed int2/int4/int8 单键、2–4 列复合键、整数机构和 NOT_NULL 实库通过 | 视图/UNLOGGED/分区/继承/非空 reloptions/规则/临时/RLS/安全标签、文本和其他规则、物理恢复代际；不能套 PG 或 Greenplum |
| OPENGAUSS | 7.0 RC3/A 永久行存 heap 身份；有限整数/文本/混合键与 NOT_NULL | 其他表形态/类型/规则/部署模式，RLS/脱敏等未证明路径保持拒绝 |
| ELASTICSEARCH | 8.15.3 已实测：同名删除重建改变 `index.uuid`，没有 TRUNCATE；`_delete_by_query` 不改变 uuid、`history_uuid` 或 `translog_uuid`。序列号和段代际随写入变化。数据流 generation 与后备索引名在删流重建后复用，因此未发凭证 | 找到每次清空都改变、写入不改变的原生代际后，才能做整数键、机构和 NOT_NULL。快照恢复、多节点和开启安全后的权限未测。不能套关系库截断代际。#17 的产品驱动仍不执行改写后的 SELECT |
| OCEANBASE | CE 4.3.5.6/MySQL case 1 普通行存身份；整数键/机构和 NOT_NULL | Oracle 模式、文本/其他类型/规则和完整集群 |
| UXDB | UXsinoDB 2.1.0.3 永久 heap 身份；signed int2/int4/int8 单键、2–4 列复合键、整数机构和 NOT_NULL 实库通过。#12 COPY 事务/未知后回退代码已修，PG 协议模型不等于原生 COPY 验收 | 视图/物化视图/UNLOGGED/分区/继承/非空 reloptions/规则/临时/RLS、numeric 与文本、其他规则、物理恢复代际、原生 COPY 故障；全局临时表和外部表本轮没有建成；不能套 PostgreSQL、瀚高、Greenplum 或金仓 |

### 10.3 环境、许可与支持判定

- 商业开发/测试许可已有用户确认，不重复用“所有商业库均未获许可”作为阻塞；但具体介质、账号、厂商驱动再分发许可和兼容版本仍须落实。
- DB2 已有普通权限启动失败记录。2026-09-29 同一镜像摘要再次失败：四次 SQL1042C，`INSTANCE_MEMORY=262144` 也不能让 `db2sysc` 留下诊断，Q11DB 未创建。不能把容器 running 当数据库可用。独立测试 VM/数据库或仅该一次性容器的额外特权需另行明确，已有软件许可不等于 `--privileged` 授权。见[2026-09-25 记录](../reviews/2026-09-25-quality-db2-environment.md)和[2026-09-29 再试](../reviews/2026-09-29-quality-db2-environment.md)。
- MariaDB 受管代际依赖 DBA 初始化和恢复/克隆后的轮换，详见[部署说明](../operations/mariadb-managed-source-generation.md)；不允许普通质检账号自动修改业务源库来生成身份。
- 一个产品完成后关闭自有测试库，再开始下一个；只清理确认属于本任务的容器和一次性卷，保留用户其他容器/卷。
- 每个产品交付“具体版本 × metadata/DDL/read/write/full sync/incremental sync × #11/#17 需要的能力”矩阵。未调用或未测能力标未知/待验证；默认拒绝只是临时保护，不等于用户要求的适配完成。

**#11 完成条件：**全部产品的要求逐项有实现和原生证据，历史/恢复/权限/类型限制有明确处置，实际 Governance 复检链可复现；不能只累计组件测试数量或新增枚举值后关闭整项。

## 11. 其他原编号：主要代码已修，但仍需完成的验收

以下不重新算作第 2 节以外的新“未修核心项”，统一进入集成验收；避免接手者重做已修代码。

| 原编号 | 已有内容 | 仍需完成/注意 |
| --- | --- | --- |
| #1–#6 | ETL/ACCESS 原子受理、可靠领取、跨节点停止、真实终止确认、租约及回写隔离 | 实际引擎/网关/双完整应用故障验收；不能推广到旧推送/主数据派发 |
| #8 | 血缘 outbox、租约补发、死信/人工重放、重复/乱序保护 | 真实 Governance + XXL-Job + 跨节点 + 页面权限。旧文“6 项 PG 跳过”已被后续覆盖：本地报告 2026-09-25 12:07:48 为 6 项、零失败/错误/跳过；本次未重跑 |
| #9、#10 | 独立复检轮次、同请求幂等、命中前重验当前对象权限 | 真实转派/撤权、跨主机/调度联调；事务内旧远端富化/Guard 可另评短事务优化，不能删除门禁 |
| #13、#14 | 四类整改/退回/轮次展示、责任人聚合及契约 | 真实 System/Governance/网关/浏览器及租户/权限场景 |
| #16 | 批准字段冻结失败不回退 ALL；缺证据拒绝 | 历史申请按已定撤销/重申请处理，与 #18/#19 联合验收；不能用当前元数据补签旧批准 |
| #20 | 在线工具及文档 V2 默认 | 真实第三方客户端、旧协议适用清单、退出安排及多节点 nonce；密钥重置不能宣传为平滑双钥轮换 |
| #21–#24 | 恢复操作持久状态、完整请求摘要、稳定身份、定时/人工对账 | 长任务/提供方重启/丢回执/部分写入及原表恢复保护；破坏性场景仅获准隔离对象 |
| #25–#28 | ER 加载、类型显示、无损大 ID、探查旧响应隔离 | 列表跳转/直达刷新/无参数/无权限/快速切换的真实浏览器回归；大 ID `9007199254740993` 请求与回显不变 |
| #29–#31 | 中台类型错误、旧测试路径及组件接入测试已处理 | 后续前端改动继续检查中台切片；全应用非中台旧错不纳入本次。组件挂载不能冒充浏览器验收 |
| #32 | 用户明确确认结项 | 不重新打开任务；完整新库基线导入尚未验证、截断和缺约束风险仍需告知部署方 |

## 12. 接手代码与测试入口

以下均为仓库相对路径，便于换电脑交接。`J(module)` 表示 `yudao-module-<module>/yudao-module-<module>-server/src/main/java/cn/iocoder/yudao/module/<module>/`；测试对应 `src/test/java`。

| 领域 | 优先入口 |
| --- | --- |
| 推送防重 | `J(dataforge)service/push/{DataPushRunCoordinator,DataPushExecutor,DataPushOpsSupport,DataPushTaskServiceImpl,HttpPushRetrySupport}.java`；`dal/mysql/push/`；`job/push/DataPushSyncJob.java` |
| 推送状态 | `yudao-module-dataforge/yudao-module-dataforge-api/src/main/java/cn/iocoder/yudao/module/dataforge/enums/push/PushRunStatusEnum.java` |
| 同步证据 | `J(dataforge)service/access/{AccessSyncEvidence,SyncBatchObservationService,SyncBoundaryService}.java`；API 模块 `api/migration/SyncBatchEvidenceApi.java` |
| 质量判定 | `J(governance)service/quality/{QualityTaskExecutor,QualityReadEvidence,QualityRecheckEvidence,QualitySyncEvidenceCollector,QualityWorkOrderServiceImpl}.java` |
| 主数据派发 | `J(governance)service/master/{MasterDistributeExecutor,MasterDistributeRunCoordinator,MasterDistributeRunReviewService}.java`；`MasterDistributeController` 与日志 Mapper |
| 数据库技术 | `yudao-framework/yudao-spring-boot-starter-biz-dbswitch/src/main/java/cn/iocoder/yudao/framework/dbswitch/` 下 `core/database/`、`core/service/RestrictedSelectGuard.java`、`dbcommon/database/JdbcBatchExecutor.java`、`dbwriter/` |
| 共享申请/交付 | `J(asset)service/market/{MarketApplyServiceImpl,MarketDeliveryService,MarketDeliveryCoordinator,MarketDeliveryDisposalService,MarketDeliveryReviewService,MarketDemandServiceImpl}.java`；处置绑定/批量接口见 `MarketApplyController` 的 `/delivery-disposal(-batch)` |
| 来源授权 | `J(supply)service/app/grant/{SupplyGrantCoordinator,SupplyGrantReadService,SupplyGrantPolicy,SupplyGrantReviewService,SupplyGrantDisposalService}.java`；孤立旧 ADMIN 处置结论与完整清点见 `SupplyAppController` 的 `/api-auth/legacy-admin-disposal(s)` 与 `/api-auth/review-inventory`；审计表见 0070 |
| 前端共享契约 | 前端 `apps/web-antdv-next/src/api/asset/market/index.ts` 及对应页面；修改前先定位调用方，不按旧文目录猜测 |
| 前端整改/ER | 前端 `apps/web-antdv-next/src/views/governance/quality/work-order/`、`views/modeling/logicmodel/er.vue`、`views/modeling/physicalmodel/er.vue` |
| 前端推送状态 | 前端 `apps/web-antdv-next/src/api/dataforge/datapush/task/index.ts`、`views/dataforge/datapush/task/{run-state.ts,index.vue,modules/}`；父页与状态专测同目录 |

优先保留并扩展以下现有测试：

- 推送：`DataPushExecutionOutcomeTest`（原反例已绿）、`DataPushRunCoordinatorPostgreSqlTest`（真实 PG 39 项，包含原调度反例）、`DbPushOutcomeTest`、`HttpPushOutcomeTest`、`HttpPushRetrySupportTest`。
- 事务/COPY：`JdbcBatchOutcomeTest`、`JdbcBatchOutcomePostgreSqlTest`、`CopyOutcomeTest`、`CopyOutcomePostgreSqlTest`、`SimpleRowWriterOutcomeTest`、`MigrationTransferPostgreSqlTest`。
- 证据/复检：`AccessSyncEvidencePostgreSqlTest`、`SyncEvidenceRpcSecurityTest`、`QualitySyncEvidenceCollectorTest`、`QualityRecheckEvidenceTest`、`QualityWorkOrderRecheckPostgreSqlTest`、`MasterDistributeOutcomeTest`。
- 容量/血缘：`RunCapacityScalePostgreSqlTest`、`AccessLineageDeliveryPostgreSqlTest`。
- 共享：`SupplyGrantSourcePostgresTest`、`SupplyGrantReadServiceTest`、`MarketDeliveryLeasePostgresTest`、`MarketDeliveryCoordinatorTest`、`MarketDemandServiceImplTest`，以及申请/续期专项实库测试。

### 12.1 最先可复跑的防回归反例

本机已知工具为 Java 25.0.3、Maven 3.9.11。先确认本机安装仍有效，不能拿其他 JDK 的失败判断业务回归：

```powershell
$env:JAVA_HOME = 'D:/Environment/JDK/corretto-25.0.3'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
& 'D:/Environment/apache-maven-3.9.11/bin/mvn.cmd' -o -q -pl :yudao-module-dataforge-server -am '-Dtest=DataPushExecutionOutcomeTest,DbPushOutcomeTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

当前预期：7 项全部通过；同一 UNKNOWN=5 断言已经转绿。反例使用实际执行器 + 模拟通道，不需要业务库，不能代替实库/多节点验证。最新完整命令和 107 项 PG 见[第九阶段记录](../reviews/2026-09-26-quality-push-manual-resolution.md)，HTTP 历史接收端反例见[第七阶段](../reviews/2026-09-25-quality-push-persistent-unknown.md)。

其他实库命令与配置读取对应专项文档和测试源码，不照抄已清理容器的 URL。环境未就绪导致 skipped 时必须明确记录。组件改动先测组件，再测 DataForge/Governance/Asset/Supply 中本次受影响的直接调用方；共享运行配置变动补上下文与 Jackson 2/3 线协议检查。

### 12.2 本次接续的验证口径

下列为第九阶段历史验证口径；最新第十六阶段结果见文末和[专项记录](../reviews/2026-09-27-quality-master-mq-confirmation.md)，不要累加历史测试数。

- 2026-09-26 第九阶段新执行后端专项及消费者回归：29 类 328 项，真实 PG 107，零失败/错误/跳过。逐个读取本轮 Surefire XML，不累加同一类多次运行。
- 本批前端 4 文件 14 项 Vitest；本批 7 文件 ESLint、oxfmt，两个 Vue 的 Stylelint 和 UI 边界通过。实际页面挂载仍不等于浏览器/完整服务验收。
- 本批已重跑全应用类型检查：退出非零，仍为 61 条 TS 诊断，在 Monaco、AI、BPM、CRM、HRM、IM、Mall、System，中台目录输出 0 条；不能称全应用 typecheck 通过。未运行完整前端 quality/build；范围外问题未修改。
- 未执行真实 broker、目标 DB 端到端推送、双完整应用、浏览器、新库全量初始化或业务库迁移；不要用实库协调器/模拟通道测试替代这些验收。
- 交付前执行 `python tools/quality/run_all.py`、两仓库 `git -c core.safecrlf=false diff --check` 及新文档相对链接检查，结果见文末。
- 已知另外的质量工具自测风险：`tools/quality/tests/test_modeling_migration_packaging.py` 中 `CurrentBaselineTest.test_baseline_preserves_active_period_exclusion_constraint` 对全量基线有历史失败记录；该完整自测与默认 `run_all.py` 不是同一验收面。#32 用户结项，不代表断言变绿，本次未重跑这项自测。
- COPY 阶段还记录过 Boot 配置注解处理器 4.1.0 与运行时 4.1.1 不一致；这是共享构建后续评估点，未在本次复核 effective POM，不扩大本次文档任务为 BOM 改造。

## 13. SQL 执行顺序与部署边界

**2026-09-29 #19 收尾新增 0071（供应侧授权历史处置结论）、0072（重投审计唯一索引）、0073（重投独立权限），只在一次性 PostgreSQL 验证，未执行到业务库。下面是既有发布顺序，不是立即升级业务库的指令。**

当前编号目录为 0032–0075，共44个，最高0075；`datahub-center.sql` 唯一包含标记为0075。0051–0074均已交付，不因仍未跟踪而原地修改；0075本轮新增例外到期扫描状态，不是此前被拒绝的通知迁移。后续任务创建前运行 `python tools/quality/check_migrations.py --next` 重新取号，不能复用0075；交付前重扫。0066–0070 为本文件 §7/§8 的既有迁移，此前最高0073/0074均为历史检查点。

### 已有库

先依据可信执行记录核对已安装编号和各脚本头的前置状态；不得跳过未安装中间脚本，也不凭本地基线判断业务库已升级。最近相关增量顺序如下：

| 顺序 | 精确路径（后端仓库相对） | 前置与适用条件 |
| --- | --- | --- |
| 0049 | `sql/postgresql/migrations/0049_dataforge_run-datasource-capacity.sql` | 已有库已到 0048；核对历史占位，停止旧执行节点，统一两级预算 |
| 0050 | `sql/postgresql/migrations/0050_asset_market-active-application-uniqueness.sql` | 0049 后且具备 0042/0047；停旧市场写入节点，历史重复先人工核查，不能自动删 |
| 0051 | `sql/postgresql/migrations/0051_dataforge_sync-batch-evidence.sql` | 0050 后；现有 DataForge 运行表，新增 JSONB 证据，不伪造历史回填 |
| 0052 | `sql/postgresql/migrations/0052_dataforge_sync-evidence-lookup.sql` | 0051 后；普通 CREATE INDEX 会阻塞写入，评估维护窗口与磁盘 |
| 0053 | `sql/postgresql/migrations/0053_dataforge_sync-submission-boundary.sql` | 0052 后；排空旧执行节点，保留事务顺序/幂等账本，脚本锁等待上限 5 秒 |
| 0054 | `sql/postgresql/migrations/0054_dataforge_push-unknown-guard.sql` | 0053 后且完整推送表存在；先只读统计、备份，停止并排空全部旧推送节点，维护窗口执行；禁止混合版本，未知状态不自动回滚重跑 |
| 0055 | `sql/postgresql/migrations/0055_dataforge_push-durable-dispatch.sql` | 0054 后；停旧节点并排空、统计旧在途、备份；新字段/索引及旧协议在途转 UNKNOWN；新应用全节点一起升级并确认 `dataPushSyncJob` 已启用 |
| 0056 | `sql/postgresql/migrations/0056_dataforge_push-resolution-audit.sql` | 0055 后；停旧推送节点并排空、备份，增加核查字段/唯一请求索引，不自动解除历史 UNKNOWN |
| 0057 | `sql/postgresql/migrations/0057_dataforge_push-resolution-permission.sql` | 0056 后；完整 system_menu、序列、有效推送运行权限及父菜单；新增独立核查按钮，不自动授予角色 |
| 0058 | `sql/postgresql/migrations/0058_dataforge_push-alert-outbox.sql` | 0057 后；完整推送表，停旧节点并备份，创建告警/人工处置账本及扫描索引，不改原执行结果 |
| 0059 | `sql/postgresql/migrations/0059_dataforge_push-alert-template.sql` | 0058 后；完整 system_notify_template/序列，缺失或冲突拒绝；刷新模板缓存，升级应用并配置告警 Job |
| 0060 | `sql/postgresql/migrations/0060_governance_master-distribute-runtime.sql` | 0059 后且完整主数据派发表；停机排空旧派发节点、备份，隔离历史明确未知；全节点升级并启用 `masterDistributeJob` |
| 0061 | `sql/postgresql/migrations/0061_governance_master-distribute-resolution.sql` | 0060 后；停旧节点并备份，新增核查审计及唯一请求索引，不自动解除历史 UNKNOWN |
| 0062 | `sql/postgresql/migrations/0062_governance_master-distribute-resolution-permission.sql` | 0061 后；完整 system_menu/序列及唯一有效主数据 run 按钮/父菜单，独立 resolve 权限不自动授权角色 |
| 0063 | `sql/postgresql/migrations/0063_governance_master-distribute-alert-outbox.sql` | 0062 后；完整主数据任务/日志，停旧节点并备份，创建关键告警、人工审计与索引，不改原运行结果 |
| 0064 | `sql/postgresql/migrations/0064_governance_master-distribute-alert-template.sql` | 0063 后；完整 system_notify_template/序列，缺前置/禁用/冲突拒绝，刷新模板缓存后启用 Governance 三项告警 Job |
| 0065 | `sql/postgresql/migrations/0065_governance_master-distribute-stop-audit.sql` | 0064 后且 0060/0061/0063 完整；先增三列及停止审计完整性约束，再部署前后端，维护窗口评估扫描/锁表；不改历史运行、不授予新角色、不改变排期 |
| 0066 | `sql/postgresql/migrations/0066_asset_delivery-review-evidence.sql` | 0065 后；核查结论持久审计字段与唯一请求索引，不回填历史核查决定、不自动授予角色 |
| 0067 | `sql/postgresql/migrations/0067_asset_grant-approval-evidence.sql` | 0066 后；新批准原范围凭证，存在凭证时禁止清空审计表 |
| 0068 | `sql/postgresql/migrations/0068_asset_demand-match-unique.sql` | 0067 后；同需求同资产未删除记录部分唯一索引，历史重复会安全阻断升级，须先人工核查，不能自动删除 |
| 0069 | `sql/postgresql/migrations/0069_asset_delivery-disposal.sql` | 0068 后；处置审计约束/唯一请求索引/只可追加触发器，存在处置审计后禁止清空审计表 |
| 0070 | `sql/postgresql/migrations/0070_supply_legacy-admin-disposal.sql` | 0069 后且 dh_supply_app_api 完整；孤立旧 ADMIN 处置结论审计（只记录不动授权），建表/索引/只可追加触发器，不回填历史 |
| 0071 | `sql/postgresql/migrations/0071_supply_review-disposition.sql` | 0070 后且三类供应账本完整；授权历史处置结论（其余待核查分类，只记录不动授权），建表/索引/只可追加触发器，不回填历史 |
| 0072 | `sql/postgresql/migrations/0072_asset_delivery-redeliver-audit.sql` | 0071 后且 0066 复核列存在；DELIVERY_REDELIVERY 租户内请求唯一索引，同请求重试只回放首份意图 |
| 0073 | `sql/postgresql/migrations/0073_asset_delivery-redeliver-permission.sql` | 0072 后且完整 system_menu/序列及有效交付核查按钮父菜单；新增 `asset:market:redeliver` 独立权限，不自动授予角色 |
| 0074 | `sql/postgresql/migrations/0074_governance_permission-schema-binding.sql` | 0073 后且完整治理表/字段权限表；维护窗口暂停消费，新增精确 schema/核验记录/唯一索引，历史 NULL 不自动绑定；统一升级应用和前端后由管理员逐组核验，禁止回退裸名解析 |
| 0075 | `sql/postgresql/migrations/0075_governance_quality-expiry-scan-cursor.sql` | 0074后且完整质量工单/提交表；暂停原到期任务、排空旧节点，新增租户唯一扫描游标表/序列/约束，统一升级治理节点后恢复原任务；不改历史工单/通知，不清零已有位置，已在临时PG验证重放及基线相关片段 |
| 0076 | `sql/postgresql/migrations/0076_governance_quality-expiry-result-binding.sql` | 0075后且完整质量工单/动作表；维护窗口暂停到期任务、排空旧节点，新增结果五列/CHECK/租户目标唯一索引，约束及索引扫描会阻塞写；旧值保持NULL，先迁移再统一治理节点并恢复任务。真实PG首跑/重放和基线相关片段已验；不回填历史或迁移通知协议 |

已可信确认执行的脚本无需当作新迁移再跑。早于 0049 的升级必须完整核对目录和历史前置，不能把上表当成任意旧库的完整安装包。0054、0055 已在临时 PG 验证首跑/重放/选择性；0055 另验证旧结构补列后与基线推送切片的字段/索引定义一致；没有完成全部迁移链或全量基线初始化验收。

0056、0057 历史阶段在真实 PG 验证首跑/重放、核查记录保留、旧结构升级及基线相关切片、缺菜单前置/禁用冲突拒绝、角色授权不变。

0058、0059 历史阶段在真实 PG 验证告警账本首跑/重放、活动租约保留、基线相关表切片、缺模板前置/序列碰撞/禁用及内容冲突拒绝。模板夹具只证明相关 PG 结构，不代表部署库完整 System 结构已验。完整升级/回退和三项 Job 参数见[第十一阶段部署说明](../reviews/2026-09-27-quality-push-durable-alerts.md)。

0060 已验证首跑/重放、旧记录选择性、活动新协议令牌保留、原生唯一约束；本批继续回归。0061/0062 首跑/重放、审计保留、唯一约束、缺前置/禁用冲突拒绝、角色授权不变及独立基线结构/权限切片均通过。全量基线警告未消除，不把切片测试当作新库初始化验收，详见[第十三阶段](../reviews/2026-09-27-quality-master-manual-resolution.md)。

0063/0064 本批真实 PG 验证首跑/重放、租约/审计保留、唯一约束、独立基线表/索引/模板切片、缺表/序列/禁用/内容冲突拒绝及序列碰撞；不代表真实 System 服务部署已验。完整操作方法见[第十四阶段部署说明](../reviews/2026-09-27-quality-master-durable-alerts.md)。

0065 在第十七阶段真实 PG 验证重复执行、审计保留、完整性约束、独立基线结构切片等价；新增停止协议不把已发送状态改成发送前失败、不覆盖自然成功，也不以停止凭证代替核查。详情见[第十七阶段](../reviews/2026-09-27-quality-master-run-stop.md)。第十八阶段未重跑此迁移验收，不累计旧结果。

### 新库

`sql/postgresql/datahub-center.sql` 头部明确保留“截断、缺少后续定义、不可用于新建数据库”的警告。用户确认的是现有数据库正常，以及 #32 本轮结项；不是确认全量基线可安全初始化新库。需要新库交付时先取得并验证完整初始化来源，不用当前文件覆盖健康已有库。

## 14. 给接手者的启动清单

1. 阅读本文件、根/目标目录 AGENTS 和仓库版本技能；确认只改数据中台。
2. 两仓库 `git status` 核对完整工作树，包含新增 Java、前端、测试、文档及所有编号迁移（当前最高0075）；不拉取/重置来替换当前基准。
3. 原 UNKNOWN、排期、两模块 MQ 确认、告警遗漏、主数据未知误报/缺核查入口、HTTP 隐式重发、独立停止及 UXDB COPY 回退反例当前均应通过；不要按旧“主数据仍仅本机Map/无人工核查/无持久告警/无 MQ 保护/UXDB 仍隐式重投”等历史文字重做已落地协议。按第 4.3/4.5 节继续真实通知部署、自动/已生效结果对账及同次读取到数证明，UXDB 原生环境具备后另补验证。
4. 按 #12 小批交付，修复后原测试、实际 PG、直接调用方和门禁全部核对；不把部分阶段完成写成 #12 整项完成。
5. 按 #12 → #15 → #17 → #11 的剩余缺口接续；#18/#19 代码侧已收尾，部署/历史业务处置另列。需要新业务政策或额外权限时提出具体选择，不猜测外部结果。
6. 完成 #33 已具备链路验收，最后按矩阵继续 #11；每完成一个产品更新对应能力/限制并补集群路径复验。
7. 每批记录修改文件、迁移顺序、实跑/失败/跳过数量、未验证项和清理结果。未经授权不修改业务数据库、不推送代码、不替换外部客户端执行开发。

### 第十九阶段交付校验记录（最新）

- 按项目技能在 DB Switch 扩展默认拒绝的只读快照 SPI，Governance 只编排实际读取和领域凭证；非 PG 产品不继承 PostgreSQL 已验证能力。新摘要不改变复检资格，不冒充到数证明。
- 原跨快照计数反例转绿；最终组件 7 类 105 项、消费方 16 类 246 项，共 351 项（真实 PG 94），零失败/错误/跳过。既有 65 项真实整改回归完整通过；保留旧时间契约与机构查询局部失败语义。
- 最小 Spring 上下文、Jackson 2/3 契约、四个中台消费模块与 ETL 回归通过。effective POM/定向依赖复核和质量门禁通过：34 SQL、1112 错误码、200 POM、4968 Java；历史债务 76+1 与原 Boot 处理器版本不齐保留。
- 无新 SQL/前端变更，未重跑前端、未升级业务库；14:30:45 前单个 PG 容器/一次性卷清理，镜像保留。过程失败、旧测试随机误报修正、命令及边界详见[第十九阶段](../reviews/2026-09-27-quality-read-snapshot.md)。

### 第十八阶段交付校验记录（历史）

- 按项目技能复用 DB Switch 现有 JDBC 事务执行器和方言引用，不在业务模块复制技术机制；默认 UXDB COPY 工厂和显式 INSERT 模式保留，但任何 COPY 异常不再隐式改写重投。
- 组件 13 类 155 项、四个中台模块及 ETL 13 类 196 项，共 351 项（真实 PG 46）零失败/错误/跳过；含最小 Spring 上下文、Jackson 2/3 契约及未知结果消费方。没有运行 UXDB 原生测试，不能将 PG 模型视为产品通过。
- effective POM/定向依赖复核及质量门禁通过：34 SQL、1112 错误码、200 POM、4963 Java，历史债务 76+1 保留。已有 Boot 处理器 4.1.0 与依赖 4.1.1 不齐仅记录，不扩大本批改根 POM。
- 本批无新 SQL/前端变更，未重跑前端检查，未升级业务库；单个 PG 和一次性卷已于 14:01:05 前清理，镜像保留。命令、故障实测与剩余边界见[第十八阶段](../reviews/2026-09-27-quality-uxdb-copy-outcome.md)。

### 第十六阶段交付校验记录（历史）

- 原四项 MQ 反例全部转绿；Governance 独立运行级 Kafka/Rabbit 保护已接入保存/执行入口，不依赖 DataForge server，不新增通用客户端。
- 分服务串行执行，13 类最新报告去重计 204 项，真实 PG 81、Rabbit 6、Kafka 2，零失败/错误/跳过。PG 两协调器取消 + 模拟 broker 与真实 broker + 模拟源/协调器分别覆盖，不能冒充同时连接 PG/broker 的双完整应用。
- 质量门禁通过：33 SQL、1111 错误码、200 POM、4959 Java，历史债务 76+1 保留；无前端/框架/POM/新 SQL 变更，未动业务库。
- Kafka 单 broker 的 min ISR 故障假设被实测/官方源码否定，未声称通过多副本验收；缺少端到端业务幂等/结果对账/到数证明等范围继续保留。清理与详细命令见[第十六阶段](../reviews/2026-09-27-quality-master-mq-confirmation.md)。

### 第十五阶段交付校验记录（历史）

- HTTP 读完请求后丢响应的真实反例由 2 次修为 1 次完整业务 body；原响应正文异常泄露、配置进入发送后才失败两反例同样转绿。
- 10 类 156 项通过，真实 PG 75，零失败/错误/跳过。包含原协议/告警/核查、真实源库 → HTTP、部分确认/未知/禁止重跑、双协调器取消、资源释放及中断信号；SDK 全方法重试开启的 37 项另跑通过，不累计为 193。
- 按技能留在 Governance 领域配置/执行编排，复用 JDK 公开传输与 DB Switch 源读取；无框架/POM/前端/新 SQL 变更。质量门禁为 33 SQL、1111 错误码、200 POM、4955 Java，历史债务 76+1 保留。
- 单个临时 PG 和测试卷已清理、镜像保留，未动业务库；真实主数据 MQ、接收端业务幂等/回执、完整双应用/通知部署、TLS/代理/规模及质量同次读取仍待。细节见[第十五阶段](../reviews/2026-09-27-quality-master-http-delivery.md)。

### 第十四阶段交付校验记录（历史）

- 按项目技能将主数据告警生命周期留在 Governance，复用事务、租户、XXL-Job 与正式 Notify API；未改 framework、前端或范围外业务实现。
- 最终 9 类 115 项，真实 PG 71，零失败/错误/跳过；原告警数为 0 反例转绿。包含运行/告警与审计回滚、两工作者争抢、过期令牌、回执丢失、跨租户真实 Job、重放/独立基线切片及既有核查/数据库调用方回归。
- 质量门禁通过：33 SQL、1111 错误码、200 POM、4954 Java，历史接受债务 76+1 不变。前端无变更，未重新运行前端测试/类型检查/构建；此前 61 条范围外 TS 诊断不是本批新增或本批通过结果。
- 0063/0064 仅测试环境验证，未升级业务库；真实 System/XXL-Job、双完整应用、浏览器、通道网络故障及原生到数证明仍待。一次性环境清理和执行命令见[专项记录](../reviews/2026-09-27-quality-master-durable-alerts.md)。

### 第十三阶段交付校验记录（历史）

- 按项目技能将核查生命周期留在 Governance，复用事务/租户、正式权限 API 和前端命令跟踪；未改 framework 或范围外业务实现。
- 后端 7 类 81 项（真实 PG 47）及前端 6 文件 35 项通过，零失败/错误/跳过；含实际安全/校验代理、双节点幂等、回执丢失、事务回滚、跨租户及列表/详情/核查页面竞态。
- 七文件 ESLint/格式、两 Vue Stylelint、应用边界和后端质量门禁通过：31 SQL、1111 错误码、200 POM、4947 Java，历史债务 76+1 不变；typecheck 仍 61 条范围外诊断、中台 0。
- 0061/0062 未升级业务库；完整应用/浏览器/真实外部通道、部署授权与值守、自动/已生效结果对账和到数证明未验或未实现。测试环境与交付复核见[详细记录](../reviews/2026-09-27-quality-master-manual-resolution.md)。
- 11:20:40 前清理唯一自有临时 codex-q12-master-review 容器及匿名卷；测试 schema/其他连接均为 0，镜像保留，数据可由夹具重建，未动其他容器或业务库。

### 第十二阶段交付校验记录（历史）

- 按项目技能将运行状态机留在 Governance，搬数复用 DB Switch；保留所有此前本地改动，不修改范围外业务模块。
- 最终 5 类 62 项（真实 PG 34）及前端 11 项通过，零失败/错误/跳过。早期扩选的两个环境条件跳过没有计通过；最终真实执行 PG 驱动测试，其他产品明确未重验。
- 质量门禁通过：29 SQL、1108 错误码、200 POM、4943 Java；历史债务 76+1 保留。前端六文件 ESLint 0 错误/6 条测试桩风格警告，格式、页面 Stylelint、边界检查通过；全应用 typecheck 仍 61 条范围外诊断、中台 0。
- 10:58:29 前清理唯一临时 `codex-q12-master-postgres` 及匿名卷，测试 schema/其他连接均为 0，镜像保留；无业务库变更。完整应用、浏览器、真实通道故障、其他数据库、全量基线和部署值守未验，不能把本批称作主数据派发或 #12 整体完成。

### 第十一阶段交付校验记录（历史）

- 按项目技能把告警生命周期留在 DataForge，复用 System 正式 API、XXL-Job 和租户机制；未修改范围外业务模块或 framework。关键终态与告警同事务，外部通知在独立事务之外。
- 本轮 26 类 184 项（真实 PG 76）通过，零失败/错误/跳过；包含实际双通知工作者并发和丢回执重复通知模型，不代表完整应用或真实 System 投递验收。前端未改，未重跑前端类型检查、浏览器或完整构建；未重验实 broker/其他目标库。
- 质量门禁通过：28 SQL、1108 错误码、200 POM、4941 Java；历史接受债务 76+1 未清零。后端 diff 空白检查、本批文档链接和新增文件行尾检查见[详细记录](../reviews/2026-09-27-quality-push-durable-alerts.md)。
- 2026-09-27 10:33:53 前核对 ID/标签/挂载后停止并删除 `codex-q12-alert-postgres` 及匿名测试卷；测试 schema/其他连接均为 0，镜像保留、夹具可重建，未动业务库。
- 0058/0059 未部署到业务库；三项告警 Job 未在真实调度中心配置。告警缺口的代码与隔离验证已完成，#12 其余实现及部署验收继续，八项未整体结项。

### 第十阶段交付校验记录（历史）

- 按项目技能复用现有 DataForge 外部 MQ 连接器，不另建 MQ 框架；新增纯预检并保留已交付 UNKNOWN/水位边界。
- 后端 25 类 109 项（实 broker 9）及前端 23 项通过，零失败/错误/跳过。质量门禁通过：26 SQL、1108 错误码、200 POM、4935 Java；历史债务不清零。
- 前端目标文件 lint、格式和应用边界通过；全应用类型检查仍有 61 条范围外诊断，中台 0。PG 迁移/锁、全产品、浏览器、完整应用和 MQ 集群/TLS 未重验，原因与测试选择集见[详细记录](../reviews/2026-09-27-quality-push-mq-confirmation.md)。
- 2026-09-27 10:13:55 前完成本轮 Rabbit/Kafka 容器与匿名测试卷串行清理，标签检查无残留，镜像保留；无新增 SQL、未动业务库。

### 第九阶段交付校验记录（历史）

本次按 `yudao-datahub-development` 技能把推送人工处置生命周期留在 DataForge，复用权限/命令跟踪和事务边界，数据库写入继续复用 DB Switch；采用真实 PostgreSQL 验证锁、租户、CAS、事务和迁移，不用 H2 或本机 Map 证明多节点正确性。

- 后端 29 类 328 项（真实 PG 107）及前端 14 项通过；不累计历史结果。
- `python tools/quality/run_all.py`：通过；26 个编号 SQL、1108 条错误码声明、200 个 POM、4931 个 Java 源。HEAD 接受的 76 条错误码和 1 条模块边界历史债务保持，不是本次清零。
- 两仓库 `git -c core.safecrlf=false diff --check`、新/改文档相对链接与新增文件行尾空白检查通过。
- 第九阶段于 2026-09-26 11:12:12 核验 ID/标签/挂载后停止并删除 `codex-q12-review-postgres` 容器及匿名测试卷；测试 schema/连接均为 0，镜像保留、夹具可重建，未触及业务环境。第八阶段 `codex-q12-schedule-postgres` 和第七阶段 `codex-q12-push-postgres` 已分别于 10:51:26 / 前日 14:34:04 清理。
- 今天已重跑全应用类型检查，61 条非中台诊断仍在，中台目录 0；第 4.3/4.5 节待办及完整环境验收保留，#12 仍 P，八项未整体结项。
