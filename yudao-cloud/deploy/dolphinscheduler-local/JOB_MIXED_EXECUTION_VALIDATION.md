# DATAX → SQL 开发混合作业实机验收

本验收使用本机 DolphinScheduler 3.4.2 的真实 SUB_WORKFLOW 父子工作流、DataX 和 PostgreSQL SQL Worker。执行记录、子实例身份、日志、目标表行数分别核对，不使用 mock client，不通过数据库修改平台状态。

## 运行前置

- DataForge 使用当前同一平台数据库；前端、作业、集成、开发接口必须指向同一实例。本机验收地址为 `http://127.0.0.1:50026/admin-api`，页面预览为 `http://127.0.0.1:6005`。
- 集成和普通 SQL 开发使用相同的受管 DS `projectCode`、`workerGroup`、非 root `tenantCode` 和 `environmentCode`。普通 SQL 的 `dataforge.dev.environment-code` 必须在保存开发任务版本前配置；旧版本冻结默认值 `-1`，更改服务器配置后需要重新保存版本。原生开发任务继续使用其既有冻结 profile，不受此属性覆盖。
- 人工运行策略使用 `PARALLEL`，图中的边仍严格控制节点依赖。包含开发节点的作业周期保持关闭，每次人工执行重新校验冻结身份与版本。其它人工串行策略在创建执行记录前明确拒绝。
- DS Master/Worker 必须处于正常负载和注册状态。默认系统内存保护阈值为80%；触发保护时真实实例可以长期等待，不能当作成功或重复投递。
- PostgreSQL 只建立 `codex_job_e2e_` 前缀的新库、新表、新角色；使用 `CREATE_NEW`，拒绝重复覆盖，不执行清空、删除或覆盖已有表。源读取角色只有源表 SELECT，目标写入角色只有目标表 SELECT/INSERT，开发角色只有目标表 SELECT 且 `default_transaction_read_only=on`。

## 测试数据和 SQL 边界

源表结构为 `id bigint primary key, name text, amount bigint`，三行分别为 `(1, '  alice  ', 7)`、`(2, 'Bob', 11)`、`(3, 'q''Data', 13)`；目标新表初始为空，具有相同结构和主键。DataX 使用 FULL 读取和 INSERT 写入，`targetTruncate=false`、重试为0；保留空格与单引号用于逐行校验。

普通 SQL 开发保留既有受管单表投影边界，不允许聚合、HAVING、投影函数、注释或绕过物理表绑定。实际保存内容为：

```sql
SELECT id,name,amount
FROM public."codex_job_e2e_target_20261101_233720";
```

平台把已授权 SQL 包装成 `execution_row_count` 汇总，由真实 DS SQL Worker 执行。真实日志必须确认 `execution_row_count=3`（结果集本身只有1条汇总记录），并由独立只读连接逐行比对目标三条完整数据及 `amount` 合计31。脚本同时校验 SQL 子流程开始时间不早于 DataX 成功结束。

可选的故意失败方案是只读查询一个唯一、未创建的 `codex_job_e2e_missing_` 表，预期真实 SQL Worker 报 relation 不存在；不执行集成、不修改成功目标，验证后下线作业和开发任务，周期始终关闭。本轮未创建或执行此失败样例，不把计划、单测或 STOP 记录当成该失败执行的证据。

## 凭据和网络

所有平台 JWT、DS token 和数据库口令只从工作区之外、权限严格为 `0600` 的私密 JSON 读取，不提交源码。平台数据连接由正常接口保存并由既有凭据服务加密；脚本不会接受客户端指定执行身份或任意 DS 数据源编号。

Mac 平台连接 PostgreSQL 的主机回环端口在 DS 容器内需要同源路由。本次仅在非 root Worker 内启动一个只监听 `127.0.0.1:60274` 的 TCP 转发，指向同一隔离 PG 容器5432；它不处理 SQL、不注入凭据、不生成执行结果。DS 容器重启后必须重建受管对象和该路由，不能沿用内存 H2 中已失效的编码。

## 可复用核验

`job_mixed_smoke.py` 仅使用 Python 标准库。默认核验既有记录；只有显式传入 `--run` 才真实触发。本机 `docker` 和只读查询角色用于独立目标核验。私密配置包含 `accessToken`、`ds.server/token`、`roles.query.name/password`（以及本次日志需要扫描的其它 fixture 角色口令）；公开 fixture 包含数据库、容器、目标表、预期完整行、共同 target，公开 tasks 包含 jobId、jobRevision、requestId、jobName。

```bash
python3 deploy/dolphinscheduler-local/job_mixed_smoke.py \
  --config /private/path/private.json \
  --fixture /path/fixture.json \
  --tasks /path/tasks.json \
  --record-id RECORD_ID \
  --output-dir /tmp/job-mixed-read-only-proof
```

首次执行必须使用唯一新 requestId、空的新目标表和已保存上线的图；指定 `--run --idempotency-check`。重复同一 requestId 的验收必须返回同一个平台父记录、仅一个 DS 父实例且目标仍是三行。脚本不自动清空目标或重新触发超时记录。失败样例使用 `--expected-state FAILURE`，目标仍须保留成功样例的三行。

本轮最终复验后已移除临时登录JWT；再次核验需通过真实登录更新外部0600配置，受管运行配置与fixture凭据保持在源码之外。

证据在保存前逐一扫描实际口令、DS token、平台 JWT 并脱敏，查看和下载字节必须一致；报告只包含泄漏检测布尔值，不包含命中片段。DS 子实例通过正反双向父关系确认，成功必须恰好两个不同子定义/实例、一个 DATAX 和一个 SQL 原生 Worker，不能用空集合推断全部成功。

## 实测记录

2026-11-02（Asia/Shanghai）真实执行通过，公开脱敏证据在 [执行证据](./verification/job-mixed-20261102-execution.json)、[真实SQL汇总](./verification/job-mixed-20261102-sql-worker-summary-proof.json)、[交付脚本默认只读复验](./verification/job-mixed-20261102-reusable-report.json)和[最终浏览器验收](./verification/job-mixed-20261102-ui.json)。配置图保留供页面查看，三条原有演示作业未替换。

| 对象 | 平台任务/版本 | 运维身份与记录 | DS 子实例 / Worker | 真实终态 |
| --- | --- | --- | --- | --- |
| 混合作业 | Job 11，revision 4 | JOB / PLATFORM / 5 | 父50，定义188537373107168 | SUCCESS |
| 集成 | Integration 1001 | INTEGRATION / MANAGED / 180829751977008 | 子52 / DATAX Worker52 | SUCCESS |
| 开发 | Dev 12，版本15 | DEVELOPMENT / PLATFORM / 9 | 子54 / SQL Worker55 | SUCCESS |

DataX 子流程00:11:47成功结束；SQL 子流程00:11:54开始，实际 SQL Worker00:13:48执行并成功。两个子实例与父50均完成正反双向身份核对；成功恰好包含两个不同子定义/实例及 DATAX、SQL 原生 Worker。

独立只读角色核验目标三条完整行与源一致，count=3、amount合计31。DS55原始日志仅在内存中提取 `execution_row_count=3` 并留摘要/哈希，不公开原始 SQL 结果续行；平台原有续行脱敏保持原样。三个平台运维身份的查看/下载日志字节一致，实际角色口令、DS token、JWT扫描的 `secretLeakDetected=false`。

交付脚本在最终后端修复部署后，使用 `--record-id 5`、无 `--run`、无幂等POST的默认只读模式复验为PASS，报告记录 `executionMode=READ_ONLY`、`triggeredNewRun=false`。

相同 requestId 重复调用返回同一平台记录5；DS该专属父定义仍仅1个实例，目标仍为同样三行。首个 SERIAL_WAIT 人工尝试记录4 / DS49 在任何业务任务运行前已通过平台受管停止，并核实平台 STOPPED、DS STOP；保留独立审计，不覆盖或伪装为成功。

### 本机资源维护与范围

本机 Docker VM约8GiB，DS默认80%系统内存门限在执行期间多次触发，真实实例曾等待命令消费。本轮在核实旧XXL admin无活动执行后进行了标准内存回收、受控重启及最后最多45秒的短暂停机窗口，并临时停止无客户端连接的旧隔离PG-test；未重启DS、未清空H2、未直接修改执行状态或已有业务表。原父50持续运行，未因资源等待重新投递。

最终旧PG-test与XXL admin均已恢复运行，admin HTTP200，监控job2在00:15的trigger/handle均200。短暂停机期间00:14分钟槽未调度，存在1分钟采样缺口；不能称为对监控无影响。资源门限没有被永久解决，未来负载仍可能导致等待；本记录只是一次真实成功验收及恢复证明。

本次范围只覆盖 DATAX → 普通 PostgreSQL SQL 开发。故意失败样例本轮未执行。Spark/Flink/存储过程等原生开发类型继续依赖既有冻结 profile、身份与独立终态门禁，本次不声称实测。

### 交付检查

最终浏览器9项检查通过：父子实例身份与状态、正常日期、三个真实日志的搜索高亮与下载、独立集成和开发实例检索、新建默认并行、业务分页浅色与原导航主题。页面错误和HTTP错误均为空；临时登录状态已删除，独立浏览器已关闭。

后端针对性回归、真实PostgreSQL筛选与日期契约、迁移检查均通过。前端本次文件的定向lint、15项相关测试、类型检查与最终生产构建通过。完整 `quality:antdv-next` 最近重跑在其他数据开发文件的lint规则报错阶段终止，不能据此宣称最终全仓门禁通过。
