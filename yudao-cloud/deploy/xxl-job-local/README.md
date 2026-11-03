# 本地 XXL-Job 测试环境

使用 Docker Desktop 运行 XXL-Job Admin 2.4.0 和独立 MySQL，供宿主机上的 DataForge 执行器测试。管理端仅绑定本机 `127.0.0.1:18989`，MySQL 不发布宿主机端口。

## 初始化与启动

在本目录创建被 Git 忽略的 `.env`，设置以下变量；数据库密码分别随机生成，通讯令牌必须与 DataForge 的 `xxl.job.access-token` 一致，不要提交这些值。

| 变量 | 用途 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | 本地 MySQL 管理密码 |
| `MYSQL_PASSWORD` | 本地 `xxl_job` 用户密码 |
| `XXL_JOB_ACCESS_TOKEN` | 调度中心与执行器的通讯令牌 |

下载固定版本的官方初始化脚本并校验 SHA-256：

```sh
mkdir -p runtime
curl --fail --location https://raw.githubusercontent.com/xuxueli/xxl-job/2.4.0/doc/db/tables_xxl_job.sql --output runtime/tables_xxl_job.sql
printf '%s  %s\n' ba50287b544cb4a57e8eb12996c0c2f7dbd3e0a4c536b6a74472a4b087a54196 runtime/tables_xxl_job.sql | shasum -a 256 -c -
```

确认输出 `runtime/tables_xxl_job.sql: OK` 后启动容器；校验失败时不要继续初始化：

```sh
docker compose up -d
docker compose ps
```

SQL 仅在空 MySQL 数据卷首次启动时执行，初始化的是外部 XXL-Job 管理库，不属于平台 PostgreSQL 迁移，也不会修改 DataHub 的业务表。初始化脚本来源为 [XXL-Job 2.4.0 官方仓库](https://github.com/xuxueli/xxl-job/blob/2.4.0/doc/db/tables_xxl_job.sql)。

管理入口：<http://127.0.0.1:18989/xxl-job-admin/>。官方初始化账号为 `admin`，初始密码为 `123456`。账号、任务配置和调度日志保存在 Docker 数据卷中。

## 连接本地 DataForge

DataForge 的管理端地址设置为 `http://127.0.0.1:18989/xxl-job-admin/`，执行器 AppName 为 `dataforge-server`，执行器端口固定为 `10024`。Docker 中的管理端通过 `http://host.docker.internal:10024/` 回连宿主机；执行器需使用该地址注册，或在管理端为执行器手动填写该地址。

在 IDEA 中编辑 `DataforgeServerApplication` 的本地运行配置，将以下内容填入 **Program arguments** 后重新启动服务。这些参数只覆盖当前运行配置，不修改共享 `application-local.yaml`：

```text
--xxl.job.admin.addresses=http://127.0.0.1:18989/xxl-job-admin/ --xxl.job.executor.ip=host.docker.internal --xxl.job.executor.port=10024
```

在执行器管理中创建“数据研发”，AppName 填 `dataforge-server`。在任务管理中创建以下任务，先“执行一次”验证调度与执行结果均成功，再启动周期运行：

| 配置 | 值 |
| --- | --- |
| 运行模式 | `BEAN` |
| JobHandler | `databaseMonitorCollectJob` |
| 调度类型 | `CRON` |
| 调度表达式 | `0 * * * * ?` |
| 路由策略 | 第一个 |
| 阻塞处理策略 | 丢弃后续调度 |
| 调度过期策略 | 忽略 |
| 任务超时 | 60 秒 |
| 失败重试次数 | 0 |
| 任务参数 | 留空 |

验收时同时检查调度日志的执行结果，以及 `dh_dataforge_database_sample` 在连续分钟槽中的新增记录。已有分钟槽会去重；单个数据源离线也会形成失败采样，不能把调度成功等同于所有数据源在线。

## 当前实现与验收状态

治理“数据权限”页面的配置维护、模型、持久化和只读预检保留；业务模块不再调用该页面配置驱动的行列过滤、动态脱敏、读取策略冻结或目标 SQL 授权。`SafetyPermissionApi`、`SafetySchemaScriptApi` 及同步专用 mTLS 接入已移除。没有保留绕过开关。

系统登录、角色菜单和接口权限、任务运行权限、资产分享范围、SQL 技术校验、数据源与物理表校验以及新建目标保护继续生效。独立的静态脱敏、规则工作台和分类管理继续保留。本次不修改历史采样、调度日志、业务数据、平台 SQL 或 Doris 配置。

### 监控采集记录

2026-10-30（Asia/Shanghai）已创建本地执行器组 `2`、采集任务 `2` 并启用每分钟调度。18:58:33 手动验证、18:59:00 和 19:00:00 两轮自动调度的调度结果与执行回调均为 `200`，每轮处理 7 个连接。当时 `database_id=1` 的样本由 4 条增至 7 条，19:00 样本为 `COMPLETE / SUCCESS`，响应耗时 114 ms。

上述是已完成的采集验证记录，不代表机器休眠或服务停止期间仍会采集。调度环境已通过 `docker compose config --quiet` 和官方 SQL 哈希校验。趋势按日期聚合，同一天增加分钟样本不会增加横轴日期点。

### 真实同步和读写指标仍待验收

首次真实页面验收在 `preview-table-map` 被“仅允许 SELECT”校验阻塞；后续修复和本轮权限接入清理之后，尚待完成真实同步、目标 1000 行一致性及监控读写曲线验收，不能把单元测试或调度回调成功当作该验收通过。

验收源表为 `fill-center.codex_monitor_src_20261030_1925`，含 1000 条合成数据；对应目标名为 `codex_monitor_dst_20261030_1925`。准备阶段目标尚不存在，本地 `dataAccessSyncJob` 任务 `3` 以 `STOP` 状态创建，执行前应重新核对任务、目标和运行记录。

读取、写入速度来自可关联的成功同步记录，按同步行数和执行耗时计算；连接采集本身不会产生同步吞吐量。验收期间保留同步任务及执行记录，删除任务会影响运行历史。相关前端曾完成 3 个文件、29 项测试；此前授权实现的测试日志也保留在 `runtime`，已移除代码的测试结果不作为当前实现的验证结论。本轮回归和端到端结果待单独记录。

## 规则工作台的可选本机 TLS 通道

此通道仅供 Governance 规则详情中的“敏感资产清单”分页调用 Asset `rule-page`，与 DataForge 同步、数据库监控及治理数据权限配置执行无关。只有需要在本机联调这项独立页面功能时才需要启动；规则工作台其余功能仍使用各自的现有接口。

准备脚本仅生成或复用 CA、Asset 服务端证书、Governance 客户端证书，以及两份配置。已有 `runtime/sync-tls` 目录名保留，以复用现有证书和客户端指纹；不会覆盖现有证书，也不会自动轮换。旧 DataForge/Governance 模式脚本证书、配置、PID 记录和历史日志不会被脚本删除或使用。

保持普通 `AssetServerApplication` 进程运行，以便复用其 classpath，然后在本目录执行：

```sh
python3 prepare_workbench_tls.py
python3 workbench_tls_server.py start
python3 workbench_tls_server.py status
```

Asset 独立实例仅监听 `127.0.0.1:55403`，不向 Nacos 注册、不启用 XXL-Job，要求客户端证书且只接受配置中固定的 Governance 指纹。客户端固定访问 `https://localhost:55403`。证书新签发时有效期为 30 天；材料不完整、不匹配或不足一小时有效期时准备脚本拒绝继续，不自动修复。私钥、配置和日志均位于被 Git 忽略的 `runtime/sync-tls`，不安装到系统信任库。

普通 Governance 本地运行配置保留以下 **Program arguments**，替换为本目录绝对路径后重启：

```text
--spring.config.additional-location=file:<本目录绝对路径>/runtime/sync-tls/governance-client-tls.yaml
```

Asset 服务端配置为 `runtime/sync-tls/asset-tls.yaml`，由启动脚本加载。DataForge 仅需要前文的 XXL-Job 参数，不再加载 `dataforge-tls.yaml`，也不再需要独立 Governance TLS 实例或 `55404` 端口。页面请求仍要求正常操作者身份及 `asset:sensitive-list:query` 权限；服务证书不能代替用户权限。

停止或重启 Asset TLS 实例：

```sh
python3 workbench_tls_server.py stop
python3 workbench_tls_server.py status
# 确认退出且普通 Asset 主进程已使用最新编译结果后再启动
python3 workbench_tls_server.py start
```

脚本只操作 PID 记录中、当前用户所属且主类和精确配置路径均匹配的 Asset 进程；新 PID 记录还核对启动时间，防止停止复用 PID 的其他进程。启动输出仅表示子进程已创建，需等待 `runtime/sync-tls/asset-console.log` 出现 `Started` 后使用页面。此本机辅助通道不替代常驻部署的证书配置。

## 停止与恢复

```sh
docker compose stop
docker compose up -d
```

`docker compose down` 保留数据卷；不要添加 `-v`，否则会删除本地调度配置和历史日志。Mac 休眠、Docker Desktop 退出或 DataForge 停止期间不会采集；需要全天连续采样时，应改用常驻运行环境。
