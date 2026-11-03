# 正常本地 DolphinScheduler 前后置 SQL 验收

执行编号：`20261102_1793606129`；结果：**PASS**；DS 项目：`188590885122016`。

安装插件 SHA-256：`ae0c37ce1da07427ee0cef7581f9532f2026a534011ff8bff9f858d4bced988d`。

本报告记录原正常本地 DolphinScheduler（12345）的真实 DATAX_UPSERT 执行。没有创建独立测试服务或新端口，没有创建调度周期。平台配置表单与编译器的验收由主任务另行记录，不能从本报告推定其已通过。

本次验收定义执行类型：`PARALLEL`，脚本逐次等待终态。钩子同身份互斥由 PostgreSQL 凭据行锁保证，不依赖 DS 串行队列。最终 fixture 采用 PARALLEL，单独验证 SQL 钩子事务与凭据；平台作业串行策略由主任务独立验收，不能从本报告推定。

## 运行契约

`POSTGRESQL_UPSERT_HOOKS_V2` 将每次尝试的数据写入独立暂存表，再在同一个 PostgreSQL 事务内执行前置 SQL、目标合并、后置 SQL和 COMPLETED 凭据提交。任一阶段失败则整笔发布回滚。恢复使用原 DS 工作流实例及任务身份；已提交身份只有在输入数据摘要一致时跳过，输入变化明确拒绝。新逻辑实例允许处理新数据。

本次 SQL 仅使用同一目标 schema 中的受管、事务性 UPDATE。非幂等计数器加一用于检测钩子是否重复，除零和 NOT NULL 约束用于制造真实数据库失败。没有在本次验收中运行 DDL 钩子、函数、跨 schema 或外部副作用。

## 实际执行矩阵

| 场景 | 工作流实例 | 任务实例 | DS 状态 | 行数/唯一键 | 前/后置计数 | 行差异 | 日志 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 01-normal-success | 19 | 31 | SUCCESS | 37/37 | 1/1 | 0 | [日志](verification/sql-hooks-20261102/20261102_1793606129/01-normal-success.log) |
| 02-committed-replay | 19 | 32 | SUCCESS | 37/37 | 1/1 | 0 | [日志](verification/sql-hooks-20261102/20261102_1793606129/02-committed-replay.log) |
| 03-pre-failure | 20 | 33 | FAILURE | 37/37 | 1/1 | 2 | [日志](verification/sql-hooks-20261102/20261102_1793606129/03-pre-failure.log) |
| 04-pre-recovered | 20 | 34 | SUCCESS | 37/37 | 2/2 | 0 | [日志](verification/sql-hooks-20261102/20261102_1793606129/04-pre-recovered.log) |
| 05-post-failure | 21 | 35 | FAILURE | 37/37 | 2/2 | 2 | [日志](verification/sql-hooks-20261102/20261102_1793606129/05-post-failure.log) |
| 06-post-recovered | 21 | 36 | SUCCESS | 37/37 | 3/3 | 0 | [日志](verification/sql-hooks-20261102/20261102_1793606129/06-post-recovered.log) |
| 07-recovered-replay | 21 | 37 | SUCCESS | 37/37 | 3/3 | 0 | [日志](verification/sql-hooks-20261102/20261102_1793606129/07-recovered-replay.log) |
| 08-data-failure | 22 | 38 | FAILURE | 37/37 | 3/3 | 4 | [日志](verification/sql-hooks-20261102/20261102_1793606129/08-data-failure.log) |
| 09-data-recovered | 22 | 39 | SUCCESS | 37/37 | 4/4 | 0 | [日志](verification/sql-hooks-20261102/20261102_1793606129/09-data-recovered.log) |
| 10-new-logical-run | 23 | 40 | SUCCESS | 40/40 | 5/5 | 0 | [日志](verification/sql-hooks-20261102/20261102_1793606129/10-new-logical-run.log) |
| 11-changed-source-rejected | 23 | 41 | FAILURE | 40/40 | 5/5 | 2 | [日志](verification/sql-hooks-20261102/20261102_1793606129/11-changed-source-rejected.log) |
| 12-original-source-recovered | 23 | 42 | SUCCESS | 40/40 | 5/5 | 0 | [日志](verification/sql-hooks-20261102/20261102_1793606129/12-original-source-recovered.log) |

失败场景的行差异是与已改变的当前源数据比较，不代表已提交目标出现遗漏；失败及重放均独立比较完整目标摘要与前后置计数器，要求与本次尝试前完全一致。成功场景按后置 SQL 的明确转换结果逐行进行双向 EXCEPT ALL 校验。

前置 SQL 将目标 id=1 写成临时标记，随后必须被数据合并覆盖；后置 SQL 将 id=2 写成最终标记，必须保留。该断言同时检验真实执行顺序。

## 保留对象与结束状态

- src：`fill-center.codex_sqlhooks_src_20261102_1793606129`
- target：`fill-center.codex_sqlhooks_target_20261102_1793606129`
- audit：`fill-center.codex_sqlhooks_audit_20261102_1793606129`
- control：`fill-center.codex_sqlhooks_control_20261102_1793606129`

本次完成凭据数：`5`。工作流定义、全部执行日志、每次尝试的暂存表和目标 schema 内 `_datahub_upsert_runs` 均保留；没有删除或清空业务表。暂存表的人工清理应在确认不再用于验收和恢复后进行，本脚本不自动清理。

最终实例状态：`{"19": "SUCCESS", "20": "SUCCESS", "21": "SUCCESS", "22": "SUCCESS", "23": "SUCCESS"}`。

测试周期已关闭：`True`；创建周期数：`0`。定义结束状态如下：

- `188595948391680` / `datahub_sql_hooks_20261102_1793606129_normal`：OFFLINE，周期数 0
- `188595980760320` / `datahub_sql_hooks_20261102_1793606129_pre_failure`：OFFLINE，周期数 0
- `188596014462208` / `datahub_sql_hooks_20261102_1793606129_post_failure`：OFFLINE，周期数 0
- `188596063389952` / `datahub_sql_hooks_20261102_1793606129_data_failure`：OFFLINE，周期数 0

## SQL 执行顺序

无需平台版本化 SQL 迁移。本次仅对平台已登记的外部 PostgreSQL 测试连接 1 执行独立测试表 DDL，按 src → target → audit → control 建表及写入测试数据；运行时通过 DB Switch 生成目标 schema 的凭据表和独立暂存表。之后的前置 SQL → 合并 → 后置 SQL → 完成凭据按上述原子事务执行。所有对象、数据与日志保留。

[完整结构化结果](verification/sql-hooks-20261102/20261102_1793606129/result.json)

## 正式项目、配置页与最终验证

[正式平台链路验收](SQL_HOOKS_PLATFORM_VALIDATION_20261102.md) 已通过：任务 1006 由正常网关 48083 → IDE DataForge 50024 → 同一个 DS 12345 发布和手动执行两次。实例 15/16 均 SUCCESS，五行精确数据完全一致，运维详情中的冻结 DS 身份、状态和日志对应正确。任务已下线、周期关闭、无活动实例；配置页实际核验两个 SQL 文本域可编辑，保存内容回显，字段映射勾选正确。

本轮真实执行还定位并修复了两项正常本地环境问题：

1. 持久 H2 的旧注册记录/通知可能在启动后导致同地址新节点下线。冷启动现在在独占 H2 连接中清理临时协调数据，保留业务数据和持久节点。真实文件跨 JVM 回归又复现了 DB_CLOSE_DELAY=-1、DB_CLOSE_ON_EXIT=FALSE 下短进程 COMMIT 未完成刷盘的情形；显式 SHUTDOWN 后，下一 JVM 验证清理持久生效。验证入口为 [verify_registry_restart.py](verify_registry_restart.py)。
2. 空闲节点曾因系统内存读数超过默认 0.8 被标为 BUSY。仅正常本地启动入口将默认门槛设为 0.9、Worker 工作线程限制为 2；CPU/JVM CPU 保护及负载保护仍开启，用户显式环境变量优先。实际 DS 3.4.2 的 Spring Boot Binder 已验证配置绑定。最终 [节点健康结果](verification/sql-hooks-20261102/normal-runtime-health.json) 显示 Master/Worker 均 NORMAL，Worker 线程占用为 0。

全部历史验收迭代均保留，注册恢复及资源 BUSY 导致的中断如实标为环境失败，不计入最终 12 项通过。最后核对本轮所有迭代共 13 个 DS 测试定义均为 OFFLINE，见 [定义结束状态](verification/sql-hooks-20261102/retained-definition-states.json)。没有启动独立测试服务，没有新增端口，没有清空业务表。

最终检查结果：

- 后端参数、SQL 契约、流程编译与运维详情专项 41 项通过，无跳过；Java 8/DS/DataX 实际依赖构建及 RuntimeContractTest 通过。
- 安装/恢复保护 10 项测试通过；H2 实际文件跨 JVM 持久性回归、实际 DS 配置 Binder 检查通过；后端仓库质量检查通过。
- 字段映射与数据库配置专项 16 项通过，保留了修改前失败、修改后通过的回归证据。
- 最终前端全量 429 文件、3751 测试通过，类型检查、生产构建及本次修改文件 lint 通过。测试清理阶段有既有 happy-dom AbortError 输出，进程退出码为 0。
- 整仓质量脚本最后一次运行被其他并行修改的 transform-node-editor.vue 第 130 行 no-negated-condition lint 错误拦住；未覆盖该文件。上述类型检查、全量测试和构建已分别实际运行通过，未将整仓脚本描述为全部通过。

最终插件 SHA 与本报告顶部一致；V2 仅面向文中声明的 PostgreSQL 事务 DML 范围。没有验证或开放任意函数、DDL、跨 schema、触发器或外部副作用。
