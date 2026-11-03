# 正常本地 DolphinScheduler 幂等写入接入

## 当前接入

使用原有 `desktop-linux` Docker context、`datahub-ds-local-standalone-1` 容器和 12345 端口。没有启动独立测试后端或前端，也没有使用原隔离的 Colima `job-policy` 环境。

已安装 `DATAX_UPSERT` 和 `datahubpostgresqlwriter`。正常容器日志确认注册成功。上游 DataX 插件源码按固定 SHA-256 检验，构建时运行 RuntimeContractTest。

IDE 启动从现有 `config/application-local.yaml` 导入私密配置 `deploy/dolphinscheduler-local/runtime/dev-native/application-dolphinscheduler-private.yaml`。文件保持忽略提交、0600 权限；本报告不含 token 或数据库密码。

| 项目 | 配置 |
| --- | --- |
| DS 项目 | datahub-local-runtime / 188590885122016 |
| 执行租户 | datahub（非 root） |
| 默认 Worker | default；另保留 datahub-local 别名 |
| DataX/普通开发环境 | 188590885366752 |
| Spark 环境 | 188590885388256 |
| Flink 环境 | 188590885403616 |
| 幂等插件允许组 | default、datahub-local |
| 本次已绑定平台连接 | PostgreSQL 测试连接 id=1 → 正常 DS datasource id=1 |

普通开发节点和默认 DataX 集成节点使用同一项目、Worker、租户及环境，避免混合作业因子流程继承目标不一致而无法运行。其他原生开发部署档案仍由原配置管理，不能直接套用已失效的隔离环境编号。

本次最后于 2026-11-02 15:06 从原 IDE 重启 DataforgeServerApplication（PID 70646），健康检查 UP，已加载默认 Worker 和最终配置；没有另起服务。以后仍按原方式从 IDE 启动 Gateway、System、Infra、DataForge 和现有前端即可。创建 DataX 表输出时选择“幂等写入”、有效非空唯一键，保存/检查后运行。

新平台数据连接仍须有受管 DS 绑定；未绑定连接会明确检查失败。本次没有自动为所有现存连接复制凭据。任意 PostgreSQL 表也不自动获得幂等保证：目标需满足 V1 普通表、唯一键、类型等约束，触发器/RLS/分区/前后置 SQL 不在契约内。

## 实际验证

读取平台已有 PostgreSQL 测试连接，使用独立测试表（schema `fill-center`）：

- `codex_job_normal_source_20261102`
- `codex_job_normal_target_20261102`

| 次数 | 场景 | DS 实例 / 任务实例 | 结果 | 目标行数 / 唯一主键数 | 双向 EXCEPT ALL 差异 |
| --- | --- | --- | --- | --- | --- |
| 1 | 首次写入 | 1 / 1 | SUCCESS | 37 / 37 | 0 |
| 2 | 相同输入重放 | 2 / 2 | SUCCESS | 37 / 37 | 0 |
| 3 | 修改一行并新增三行后重放 | 3 / 3 | SUCCESS | 40 / 40 | 0 |

三次任务实际优先级均为 HIGHEST。工作流编号 `188591115569120`。报告及脱敏日志保留在 [normal-upsert-20261102](verification/normal-upsert-20261102/result.json)，日志为 attempt-1.log、attempt-2.log、attempt-3.log。

本次未创建任何周期（schedulesCreated=0）；测试表、工作流和日志保留。历史隔离环境的 Worker/宿主重启验收见 `deploy/job-policy-local/IDEMPOTENT_VALIDATION_20261102.md`，它与本次正常 DS 的三次落库验收是不同证据，不混称本次又进行了宿主重启。

## 持久性与维护脚本

原正常 DS 使用内存 H2，重启会丢工作流与环境。已在确认原库无项目/环境/数据源后迁移初始 token，改为现有日志卷中的文件库：`/opt/dolphinscheduler/logs/.metadata/dolphinscheduler`。

`start-local.sh` 仅初始化不存在的数据库，后续关闭 Spring SQL 初始化，避免重复执行上游含 DROP 的建表脚本。主 compose 与 Dockerfile 已接入该启动入口和插件产物。当前容器保留其他已安装插件，仅重启同一容器；新建镜像时运行时资源应先准备好。

维护入口（正常环境本次已经执行，无需每次启动重复执行）：

```sh
python3 deploy/dolphinscheduler-local/install_datax_upsert.py \
  --private-config deploy/dolphinscheduler-local/runtime/dev-native/application-dolphinscheduler-private.yaml
python3 deploy/dolphinscheduler-local/prepare_datax_upsert.py \
  --private-config deploy/dolphinscheduler-local/runtime/dev-native/application-dolphinscheduler-private.yaml
```

安装器拒绝迁移已有业务对象的内存库，也拒绝在存在未完成实例时重启。插件包来自仓库的 `runtime/postgresql-upsert/build.py`，安装器不下载未知二进制。真实验收脚本 `normal_upsert_acceptance.py` 只使用上述独立测试表，不清空业务表。

前端最终在原 WebStorm 终端恢复为同一个 5999 服务（PID 71277），HTTP 200；使用项目声明支持的 Node 24.16.0。原终端全局 Node 26 不在 package.json 的支持范围内，未修改全局 Node 设置，也未增加测试前端。

## 后续接入：前后置 SQL V2

前后置 SQL 已通过 `POSTGRESQL_UPSERT_HOOKS_V2` 接入正常本地 DS。选择 PostgreSQL DataX 幂等模式和非空唯一键后，可配置目标 schema 内显式 `schema.table` 的事务 DML。数据完整暂存后，在一个事务中执行目标前置 SQL、目标写入、目标后置 SQL 和完成标记；同一执行恢复核对冻结配置与数据摘要，已提交的钩子跳过。无钩子的 V1 路径保留。

V2 需要目标 schema 的 CREATE 权限，以建立 `_datahub_upsert_runs` 和每次尝试的暂存表；不会自动授权。函数、子查询、DDL、事务控制和带未验证副作用的表不在此事务契约内。完整运行结果见 [SQL_HOOKS_VALIDATION_20261102.md](SQL_HOOKS_VALIDATION_20261102.md)。

正常单机文件型 H2 启动还修复了注册恢复：冷启动清理旧临时协调记录及通知，显式 SHUTDOWN 确保短进程提交已经落盘，再启动同一官方服务。持久注册数据、业务工作流和实例日志保留。`verify_registry_restart.py` 使用实际 H2 2.2.220 文件和两个独立 JVM 复现旧问题并验证修复；没有启动测试服务或端口。
