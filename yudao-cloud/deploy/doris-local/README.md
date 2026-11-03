# 本地 Doris 中台库

使用官方 Apache Doris 4.0.8 FE、BE 镜像运行一个 FE 和一个 BE，为本机 DataHub 提供 `ods`、`dwd`、`dws`、`dim` 四个分层库。镜像固定版本及 SHA-256 摘要，支持 Apple Silicon 的原生 ARM64。项目 DB Switch 的 Stream Load 准入版本为 `doris-4.0.8-rc02-bc8ea1bac6d`，不能直接替换成 4.1.x 或 `latest`。此配置用于本地开发联调，采用单副本；生产部署需要独立规划容量、备份和高可用。

## 初始化与启动

需要 Python 3、Docker Desktop 和 Docker Compose v2。Docker 可用内存至少 8 GB；FE 容器限制 2 GB / 2 CPU、Java 堆 1 GB，BE 容器限制 4 GB / 4 CPU、JNI 堆 512 MB、`mem_limit = 75%`。

在本目录执行：

```sh
python3 init.py
docker compose ps
```

脚本首次运行生成三个独立随机密码，写入被 Git 忽略、权限为 `0600` 的 `.env`。随后校验 Compose 配置，先启动 FE 并等待 SQL 健康，立即设置 admin 密码，再启动 BE、幂等注册 `127.0.0.1:9050`，等待 BE 在线后创建分层库和 datahub 账号。root 从首次启动即启用密码；admin 密码设置不依赖 BE，避免 BE 下载或启动失败后仍留下空密码管理账号。重复执行保留数据并将 admin、datahub 密码和授权与 `.env` 对齐。

仅准备凭据而不启动容器：

```sh
python3 init.py --prepare-only
```

保留并妥善备份 `.env`；已有数据卷仍使用原密码，删除该文件后重新生成密码不能重置数据卷内的 root 账号。不要将凭据或 `docker compose config` 的完整展开输出提交到 Git。

## 系统连接参数

| 参数 | 值 |
| --- | --- |
| 连接类型 | Doris |
| 用途分类 | 中台库 |
| 主机 | `127.0.0.1` |
| SQL 端口 | `9030` |
| 用户名 | `datahub` |
| 密码 | `.env` 中的 `DORIS_DATAHUB_PASSWORD` |
| ODS 数据库 | `ods` |
| DWD 数据库 | `dwd` |
| DWS 数据库 | `dws` |
| DIM 数据库 | `dim` |
| Schema | 留空 |
| FE HTTP | `http://127.0.0.1:8030` |
| BE HTTP / Stream Load | `http://127.0.0.1:8040` |

`datahub` 具有四个分层库的查询、导入、建表、变更表、删除对象和查看视图定义权限，不授予全局管理权限。管理账号为 `root`，密码对应 `.env` 中的 `DORIS_ROOT_PASSWORD`；备用管理账号 `admin` 的密码对应 `DORIS_ADMIN_PASSWORD`。

上述连接适用于运行在宿主机的 DataHub 后端。全部发布端口仅监听本机回环地址；外部服务器不能通过这些地址访问。BE 通过 `network_mode: service:fe` 与 FE 共享网络空间，对 FE 注册地址为 `127.0.0.1`，三个端口统一由 FE 容器发布，因此宿主机上的 Stream Load 客户端可跟随 FE 跳转到 `127.0.0.1:8040`。

启动包装脚本保留官方 `bin/start_fe.sh`、`bin/start_be.sh` 的 JVM 和依赖加载流程，只调整本地资源和认证配置；停止时调用官方 `stop_* --grace`，最长等待 90 秒。BE 使用官方 Docker 包装流程相同的 `SKIP_CHECK_ULIMIT=true`，跳过启动脚本对宿主内核的限制检查；较大数据量需在 Docker Linux 虚拟机中核实 `vm.max_map_count` 等参数。

## 停止、恢复与检查

```sh
docker compose stop
docker compose up -d --wait
docker compose ps
docker compose logs --tail=100 fe be
```

FE 元数据、BE 存储和日志均使用独立 Docker 命名卷，停止或重建容器后仍保留。`docker compose down` 不删除数据卷；不要追加 `-v`，否则会删除本地中台库数据。Mac 休眠或 Docker Desktop 退出期间服务不可用。

进入 MySQL 客户端：

```sh
docker compose exec fe mysql -uroot -h127.0.0.1 -P9030
```

容器内客户端通过已配置的 `MYSQL_PWD` 完成认证，不需要把密码放进命令行。检查集群状态可执行 `SHOW FRONTENDS;`、`SHOW BACKENDS;`、`SHOW DATABASES;`。

## SQL 执行顺序

`init.py` 在 FE 健康后先设置 admin 密码；FE、BE 均健康后，再创建或同步 datahub 账号、创建 `ods → dwd → dws → dim` 并按库授权。所有语句仅作用于这套外部 Doris 实例，不修改平台 PostgreSQL 结构或基线，因此没有平台编号迁移。系统已有的四条数据连接通过正常管理接口更新。

## 本次验证记录

2026-10-30（Asia/Shanghai）已部署并通过以下检查：

- FE、BE 均为 `doris-4.0.8-rc02-bc8ea1bac6d`，健康检查通过；root、admin 空密码登录被拒绝。
- `datahub` 在四个库分别执行建表、插入、查询和清理，BIGINT 与中文回读一致。
- 使用 JDK 25、MySQL Connector/J 9.7.0 和项目真实 `DorisWriterImpl`，通过原生 FE → BE Stream Load 写入 4 行，收到 `OLAP_STREAM_LOAD_VISIBLE` 回执；BIGINT、LARGEINT、DECIMAL(38,18)、DATETIME(6)、中文、空字符串和 NULL 均完整回读。
- 写入独立测试标记后执行 `docker compose down`，再运行 `python3 init.py` 重建容器；数据、密码和节点身份保留，读写与 Stream Load 复测通过。验收表均已清理。
- 通过正常认证的管理接口更新既有连接：`3=ODS`、`4=DWS`、`5=DWD`、`6=DIM`；保留编号与业务属性，账号改为 `datahub`，四条连接测试均成功，`connectionStatus=1`。
- `docker compose config --quiet`、Bash/Python 语法检查、`python3 tools/quality/run_all.py`、`git diff --check` 通过。本次未修改 Java 业务代码，未运行全量 Maven 测试。

本机验收日志与原连接配置备份位于被 Git 忽略的 `runtime/`；备份包含敏感配置，请勿提交。

官方参考：[4.0.8 Docker 运行镜像源码](https://github.com/apache/doris/tree/4.0.8/docker/runtime)、[FE 初始密码配置](https://doris.apache.org/docs/3.x/admin-manual/config/fe-config/)、[权限管理](https://doris.apache.org/docs/3.x/admin-manual/auth/authorization/internal/)、[Compose 网络模式](https://docs.docker.com/reference/compose-file/services/#network_mode)。
