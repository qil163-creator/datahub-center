# 本地 DS 与三引擎实测记录

验证日期：2026-11-01，Asia/Shanghai。以下结论来自运行中的 Docker 和真实 DS REST API，不使用模拟 DS 或用 Shell 冒充 DataX/Spark/Flink。

## 环境和结果

- 容器：`datahub-ds-local-standalone-1`，API/UI 仅发布到 `127.0.0.1:12345`。
- DS：3.4.2；服务与任务均以非 root 的 `datahub` 用户运行。
- 同一 Worker 同时安装 DataX 202308、Spark 3.5.6、Flink 1.20.3；Spark/Flink 使用独立 Java 17，DataX/DS 使用 Java 8。
- 项目：`188507725745120`；Worker 组：`datahub-integration`；三套受管环境编码记录在无凭据的 `/tmp/datahub-ds-connection.json`。
- DS JVM 堆上限 768MB；Spark 本地验收 Driver/Executor 各 512MB、1 核；Flink SQL 本地验收使用 1 slot、1 并行度。未调高或关闭 DS 的负载保护阈值。

| 验证 | DS 实例 / 任务实例 | 实际断言 | 证据 |
| --- | --- | --- | --- |
| DataX 原生 `DATAX` | 1 / 1 | `SUCCESS`，streamreader→streamwriter 实际输出三行 `1\tDATAHUB_DATAX_SMOKE` | [engines.json](verification/engines.json) |
| Spark 原生 `SPARK` | 2 / 2 | `SUCCESS`，原生 Spark SQL 的 SUM(range(10)) 输出 `DATAHUB_SPARK_SMOKE\t45` | [engines.json](verification/engines.json) |
| Flink 原生 `FLINK` | 3 / 3 | `SUCCESS`，有界 datagen→print 实际输出 `+I[DATAHUB_FLINK_SMOKE]` | [engines.json](verification/engines.json) |
| 平台生成 Spark SQL | 31 / 31 | `SUCCESS`，读取真实生成的 `target/native-sql/spark.sql`，结果保留 `ALICE\tq'Data` | [compiled-sql.json](verification/compiled-sql.json) |
| 平台生成 Flink SQL | 32 / 32 | `SUCCESS`，读取真实生成的 `target/native-sql/flink.sql`，结果为 `+I[1, ALICE, q'Data]` | [compiled-sql.json](verification/compiled-sql.json) |
| 无界实时 Flink | 28 / 28 | 安装错误传播入口后，运行中先观察实际 `+I[DATAHUB_FLINK_STREAM]`，随后停止，确认整个进程树退出 | [flink-stream.json](verification/flink-stream.json) |
| 发布、执行、日志、Cron、停止 | 10 / 10、11 / 11 | Shell 成功、Cron 上下线、长任务实际输出后停止并确认进程树退出 | [control-plane.json](verification/control-plane.json) |
| 分支、多输出、Join、拆分 | 14、15、18、29、38、40 | Spark/Flink 分支分别核对两 sink，Join 包含 NULL key 匹配，拆分核对空串/NULL；六例完整结果行均一致 | [graphs.json](verification/graphs.json) |
| 实际编译 CSV 配置 | 12 / 12、13 / 13 | 中文、带逗号字段与表头逐格一致；重复运行命中 `TxtFileWriter-02`，全部输出文件摘要不变 | [csv.json](verification/csv.json) |
| Flink SQL 失败传播 | 25 / 24 | 故意调用不存在的函数，受管入口返回非 0，DS 最终为 `FAILURE` | [sql-error.json](verification/sql-error.json) |
| 列转行与 UUID | 34 / 34、35 / 35 | Spark/Flink 均输出六行，完整字段值与 NULL 一致；每个展开行均有格式正确且互不重复的 UUID | [mixed.json](verification/mixed.json) |
| Join 预算正反例 | 38—41 | 两引擎正常预算保留三条完整值；上限 2 遇到第三行真实抛错，DS 最终 `FAILURE`，不静默截断 | [join-budget.json](verification/join-budget.json) |

原生三引擎的实际输出经过完整值复核，不能仅凭任务 JSON 中出现测试字符串判断成功。Spark 的编译 SQL 首轮曾把常量 `q'Data` 写成 `qData`；修复方言字面量为 UTF-8 二进制解码后，上表重新验证完整值通过。Flink 维持正确的标准单引号语义。

拆分验证还定位了两项跨引擎差异：Flink 1.20 需要使用从 0 开始的 `SPLIT_INDEX`，并对已确认存在的空 token 用 COALESCE 保留空串；原始 Unicode trim 正则中的控制字符还会被 SQL Client 的终端处理改写，必须通过 UTF-8 解码表达式传递。以上修复均经过实际值复验，不只检查 SQL 是否能解析。

Flink SQL Client 1.20 会在部分 SQL 校验错误后仍退出 0，原生 DS 插件据此可能误报成功。受管 `sql-client.sh` 现在用无缓冲二进制行转发保留实时输出，只存固定错误标记；发现引擎的 SQL 错误就返回非 0。坏 SQL、好 SQL、持续流和停止进程树均实际复验。早期 awk 输入缓冲导致的实时输出延迟已用 Python 无缓冲转发修正，超时的本次测试实例也已停止。

## 停止的准确含义

DS 3.4.2 接收停止请求后，实际对任务进程树发送 SIGINT。运行日志包含 `process has exited`、退出值 130 和 `Successfully killed process tree`，进程已退出；但 Worker 可能先上报 FAILED，导致工作流为 `FAILURE`，没有返回 `STOP`。

控制面烟测明确返回 `PASS_WITH_DS_STOP_STATE_MISMATCH`。实时 Flink 报告保留 `stateAfterStop: FAILURE` 和 `processTreeExitConfirmed: true`。这证明实际停止了计算进程，不证明上游 DS 的终态枚举已经修复。平台回查必须结合持久停止意图处理该差异；不能以停止 API 的成功响应直接写 `STOP`。

首次停止还暴露官方镜像缺少 `pstree`，会使工作流停留在 READY_STOP 而进程继续运行；派生镜像安装 `psmisc` 后，已重新完成实际进程树退出验证。Spark 的目录权限问题也通过 DS 服务降权到 `datahub`、同用户创建执行目录解决，没有改为 root 运行任务。

## 复验方式和范围

运行命令见 [README.md](README.md)。`prepare_runtime.py` 固定校验 DataX 与 JDBC jar 的 SHA-256；基础镜像在 Dockerfile 中同时固定版本与 manifest digest。实际执行过：

```sh
python3 deploy/dolphinscheduler-local/prepare_runtime.py
docker compose -f deploy/dolphinscheduler-local/compose.yaml config --quiet
docker compose -f deploy/dolphinscheduler-local/compose.yaml build
python3 -m py_compile deploy/dolphinscheduler-local/smoke.py deploy/dolphinscheduler-local/engines_smoke.py deploy/dolphinscheduler-local/graph_smoke.py deploy/dolphinscheduler-local/prepare_runtime.py
python3 deploy/dolphinscheduler-local/smoke.py --output /tmp/datahub-ds-smoke.json
python3 deploy/dolphinscheduler-local/engines_smoke.py --connection /tmp/datahub-ds-connection.json
python3 deploy/dolphinscheduler-local/engines_smoke.py --connection /tmp/datahub-ds-connection.json --checks compiled --compiled-sql-dir yudao-framework/yudao-spring-boot-starter-biz-etl/target/native-sql --output /tmp/datahub-ds-compiled-smoke.json
python3 deploy/dolphinscheduler-local/engines_smoke.py --connection /tmp/datahub-ds-connection.json --checks stream --output /tmp/datahub-ds-stream-smoke.json
```

认证由环境注入或隐藏输入完成。报告与源码不包含实际账号密码、token 或 session。供独立测试服务使用的短期 token 仅位于 `/tmp/datahub-ds-private.json`，权限 0600，不在仓库中；下载包与 jar 位于被 Git 忽略的 `runtime/`。

本记录证明 DS 控制面、三套真实引擎、选定转换 SQL 的值语义和实时计算进程生命周期。平台业务 API 到 DS 三引擎再到独立 PostgreSQL 的严格三行搬数、Cron 上下线、普通组件图持续流和停止也已完成，见 [平台接口验收](PLATFORM_API_VALIDATION.md) 与 [platform-api.json](verification/platform-api.json)。实时平台实例 37 真实新增 3 行后停止，运行中修改被拒绝，平台保留停止意图并显示实际远端 FAILURE，确认进程树退出。

这些结果不代表全部 49 个组件或所有外部数据库/消息系统已端到端验收。DS standalone 使用内存 H2，进程重启后项目、环境和 token 会重新生成，必须重新准备并更新应用受管配置。
