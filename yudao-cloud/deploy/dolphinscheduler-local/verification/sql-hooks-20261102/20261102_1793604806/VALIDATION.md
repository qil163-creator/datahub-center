# 正常本地 DolphinScheduler 前后置 SQL 验收

执行编号：`20261102_1793604806`；结果：**PASS**；DS 项目：`188590885122016`。

安装插件 SHA-256：`bf9d604a7f570ca23c2ce197a03f1075db122563647941e9fd5bc77c6e218f1b`。

本报告记录原正常本地 DolphinScheduler（12345）的真实 DATAX_UPSERT 执行。没有创建独立测试服务或新端口，没有创建调度周期。平台配置表单与编译器的验收由主任务另行记录，不能从本报告推定其已通过。

## 运行契约

`POSTGRESQL_UPSERT_HOOKS_V2` 将每次尝试的数据写入独立暂存表，再在同一个 PostgreSQL 事务内执行前置 SQL、目标合并、后置 SQL和 COMPLETED 凭据提交。任一阶段失败则整笔发布回滚。恢复使用原 DS 工作流实例及任务身份；已提交身份只有在输入数据摘要一致时跳过，输入变化明确拒绝。新逻辑实例允许处理新数据。

本次 SQL 仅使用同一目标 schema 中的受管、事务性 UPDATE。非幂等计数器加一用于检测钩子是否重复，除零和 NOT NULL 约束用于制造真实数据库失败。没有在本次验收中运行 DDL 钩子、函数、跨 schema 或外部副作用。

## 实际执行矩阵

| 场景 | 工作流实例 | 任务实例 | DS 状态 | 行数/唯一键 | 前/后置计数 | 行差异 | 日志 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 01-normal-success | 6 | 6 | SUCCESS | 37/37 | 1/1 | 0 | [日志](01-normal-success.log) |
| 02-committed-replay | 6 | 7 | SUCCESS | 37/37 | 1/1 | 0 | [日志](02-committed-replay.log) |
| 03-pre-failure | 7 | 8 | FAILURE | 37/37 | 1/1 | 2 | [日志](03-pre-failure.log) |
| 04-pre-recovered | 7 | 9 | SUCCESS | 37/37 | 2/2 | 0 | [日志](04-pre-recovered.log) |
| 05-post-failure | 8 | 10 | FAILURE | 37/37 | 2/2 | 2 | [日志](05-post-failure.log) |
| 06-post-recovered | 8 | 11 | SUCCESS | 37/37 | 3/3 | 0 | [日志](06-post-recovered.log) |
| 07-recovered-replay | 8 | 12 | SUCCESS | 37/37 | 3/3 | 0 | [日志](07-recovered-replay.log) |
| 08-data-failure | 9 | 13 | FAILURE | 37/37 | 3/3 | 4 | [日志](08-data-failure.log) |
| 09-data-recovered | 9 | 14 | SUCCESS | 37/37 | 4/4 | 0 | [日志](09-data-recovered.log) |
| 10-new-logical-run | 10 | 15 | SUCCESS | 40/40 | 5/5 | 0 | [日志](10-new-logical-run.log) |
| 11-changed-source-rejected | 10 | 16 | FAILURE | 40/40 | 5/5 | 2 | [日志](11-changed-source-rejected.log) |
| 12-original-source-recovered | 10 | 17 | SUCCESS | 40/40 | 5/5 | 0 | [日志](12-original-source-recovered.log) |

失败场景的行差异是与已改变的当前源数据比较，不代表已提交目标出现遗漏；失败及重放均独立比较完整目标摘要与前后置计数器，要求与本次尝试前完全一致。成功场景按后置 SQL 的明确转换结果逐行进行双向 EXCEPT ALL 校验。

前置 SQL 将目标 id=1 写成临时标记，随后必须被数据合并覆盖；后置 SQL 将 id=2 写成最终标记，必须保留。该断言同时检验真实执行顺序。

## 保留对象与结束状态

- src：`fill-center.codex_sqlhooks_src_20261102_1793604806`
- target：`fill-center.codex_sqlhooks_target_20261102_1793604806`
- audit：`fill-center.codex_sqlhooks_audit_20261102_1793604806`
- control：`fill-center.codex_sqlhooks_control_20261102_1793604806`

本次完成凭据数：`5`。工作流定义、全部执行日志、每次尝试的暂存表和目标 schema 内 `_datahub_upsert_runs` 均保留；没有删除或清空业务表。暂存表的人工清理应在确认不再用于验收和恢复后进行，本脚本不自动清理。

最终实例状态：`{"6": "SUCCESS", "7": "SUCCESS", "8": "SUCCESS", "9": "SUCCESS", "10": "SUCCESS"}`。

测试周期已关闭：`True`；创建周期数：`0`。定义结束状态如下：

- `188594593562784` / `datahub_sql_hooks_20261102_1793604806_normal`：OFFLINE，周期数 0
- `188594631324832` / `datahub_sql_hooks_20261102_1793604806_pre_failure`：OFFLINE，周期数 0
- `188594669034656` / `datahub_sql_hooks_20261102_1793604806_post_failure`：OFFLINE，周期数 0
- `188594723304608` / `datahub_sql_hooks_20261102_1793604806_data_failure`：OFFLINE，周期数 0

## SQL 执行顺序

无需平台版本化 SQL 迁移。本次仅对平台已登记的外部 PostgreSQL 测试连接 1 执行独立测试表 DDL，按 src → target → audit → control 建表及写入测试数据；运行时通过 DB Switch 生成目标 schema 的凭据表和独立暂存表。之后的前置 SQL → 合并 → 后置 SQL → 完成凭据按上述原子事务执行。所有对象、数据与日志保留。

[完整结构化结果](result.json)
