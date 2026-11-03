# 受管 PostgreSQL DataX 幂等写入（V1/V2）

本扩展保留 `DATAX` 的普通追加行为，新增独立的 `DATAX_UPSERT` 任务类型与 `datahubpostgresqlwriter`。旧 Worker 未安装扩展时找不到该任务类型，不能静默以 INSERT 执行。发布前，管理员必须在全部相应 Master/API/Worker 安装插件，并仅将已安装的工作组加入 `dataforge.integration.datax-upsert-worker-groups`。

界面选“幂等写入（PostgreSQL DataX）”并指定 `upsertKeys`，现有流程 JSON 持久化这些设置，不新增平台表。凭据仍由 DS 的受管数据源解析；平台定义只携带数据源编号、表身份、键列和受管事务钩子，不支持用户自定义 JDBC 凭据。

## V2 前后置 SQL 与恢复

配置前置或后置 SQL 时使用独立的 `POSTGRESQL_UPSERT_HOOKS_V2` 契约；无钩子的 `POSTGRESQL_UPSERT_V1` 保留原来的批次行为。V2 每个物理尝试创建全新的、持久化的 `_datahub_upsert_stage_*` 暂存表；完整数据先按唯一键写入暂存表，失败尝试的残行不会混入下一次尝试。

全部暂存任务成功后，在一个 PostgreSQL 事务内执行：前置 SQL → 暂存数据 UPSERT 到业务目标 → 后置 SQL → 写入完成凭据 → 提交。任一阶段失败均回滚业务目标、SQL 钩子和完成凭据。暂存批次提交日志不代表业务目标已经发布，必须以 `publish-committed` 和调度实例状态判断最终结果。

执行身份来自正常 DS 的项目、工作流定义、工作流实例和节点名称，不包含重试时变化的物理任务实例编号。目标 schema 中的 `_datahub_upsert_runs` 持久化冻结配置摘要和完成凭据；配置或数据变化不能冒充已完成执行的恢复。同一逻辑执行重放时以完成凭据及排序后的暂存数据摘要核对；已经提交的执行返回 `replay-skipped`，前后置 SQL 不再重复执行。新建工作流实例属于新执行，会正常执行其钩子。连接丢失导致提交结果未知时，恢复仍通过同一个凭据判定是否已完成，不推测提交失败。

SQL 支持显式限定同一目标 schema 的单表 `INSERT ... (列) VALUES ...`、`UPDATE ... SET ...`、`DELETE FROM ...`，可包含普通字符串、算术和条件表达式。每阶段最多 32 条、16000 字符。不支持事务控制、DDL、查询/子查询、存储过程、函数、动态参数、反斜线字符串转义或运行时保留表。所有 SQL 使用固定 `pg_catalog` 搜索路径；不会通过调用函数发送外部消息或产生不可回滚副作用。

V2 的业务目标、钩子表和执行凭据必须是持久化 heap 普通表；不支持继承/分区、用户触发器、规则、RLS、外键、CHECK/排除约束、默认值/序列/生成列、自定义类型以及自定义或表达式索引。所有涉及表均先通过真实 PostgreSQL 目录核验。目标仍须具备完整的非空普通唯一键。目标账户额外需要目标 schema 的 CREATE 权限以创建执行凭据及暂存表；系统不会自动扩大账户权限。

失败与成功的暂存表、凭据及日志均保留供验收和排查，当前版本不自动清理。应按业务保留期在确认相关逻辑执行已不再恢复后显式清理；不要删除仍可能恢复的完成凭据。

## 写入语义

- PostgreSQL 12+ 普通表，键必须是完整、非空、非延迟、非部分、非表达式唯一索引；字段映射必须包含全部键列。暂不支持用户触发器、RLS、分区表以及尚未验证的扩展数据类型。
- 每批使用同一连接持有目标写锁、核验真实唯一索引，再执行参数化 `INSERT ... ON CONFLICT (...) DO UPDATE`，成功后提交。相同字段值重放不产生 UPDATE；键列不会被更新。
- 任何批次错误均回滚并令任务失败，不降级为逐行提交或收集脏数据继续成功。连接丢失造成提交结果未知时，恢复重放相同业务键。
- 数据语义是按业务键收敛的幂等 UPSERT；不是跨外部副作用的分布式 exactly-once。恢复期间源数据应保持可重放、同键值确定。追加历史场景需将业务批次/版本加入唯一键；不能仅按实体 ID 覆盖本应保留的历史版本。源端删除、CDC 游标和多表原子提交不属于本能力。
- 目标账户需 SELECT、INSERT、UPDATE 权限。平台不自动清表、创建唯一约束或删除旧重复数据。已有无唯一键的 INSERT 作业仍按原有语义执行。

## 组件归属与构建

检索并复用 DB Switch 的 `PostgresUpsertWriter`。将其现有唯一键核验提取到无框架依赖的 `PostgresUpsertSql`；平台写入器和 DataX 插件编译同一份源码。事务由 DataX 批次持有，JDBC 类型绑定复用官方 `CommonRdbmsWriter`。

DS 3.4.2 的原生 DataX 参数没有 PostgreSQL UPSERT 入口。`build.py` 下载固定版本的官方任务实现并核对 SHA-256，保留其许可头；只改为独立类、独立参数类型和指定新 Writer，不替换原插件。Java 8 运行契约检查与编译都使用验收镜像的实际 DataX/DS 依赖。构建结果不含私密配置，不纳入版本控制。

```sh
python3 yudao-framework/yudao-spring-boot-starter-biz-dolphinscheduler/runtime/postgresql-upsert/build.py \
  --context colima-job-policy --container datahub-job-policy-scheduler-1 \
  --lombok "$LOMBOK_JAR" --output deploy/job-policy-local/datax-upsert.tar.gz
```

生成归档安装到 `/opt/dolphinscheduler/plugins/task-plugins` 与 `/opt/datax/plugin/writer/datahubpostgresqlwriter`。以现有 `runtime.tar.gz`、生成归档、`Dockerfile` 和 `start-upsert-worker.sh` 为隔离构建上下文；保留 `policy-pg-data` 和 `policy-ds-logs` 持久卷。先部署执行环境，再部署平台代码并开启工作组配置。更新已有工作组前应停止接收新任务，等待其活动实例结束。

上游依据：[DataX PostgreSQL Writer](https://github.com/alibaba/DataX/blob/master/postgresqlwriter/doc/postgresqlwriter.md)、[固定 DS 3.4.2 DataxTask](https://github.com/apache/dolphinscheduler/blob/3.4.2/dolphinscheduler-task-plugin/dolphinscheduler-task-datax/src/main/java/org/apache/dolphinscheduler/plugin/task/datax/DataxTask.java)。实际兼容性以固定发行依赖的构建检查及隔离 PostgreSQL/DataX 故障验收为准。
