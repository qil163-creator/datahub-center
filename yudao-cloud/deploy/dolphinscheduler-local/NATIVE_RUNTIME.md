# 数据开发本地原生运行监督

本配置复用现有 Worker 中的 Flink 1.20.3、Spark 3.5.6 与独立 Java 17，不创建 YARN 集群。只允许受管 `FLINK_SQL`、`SPARK_SQL`；已有无 `dh_native_` 标识的数据集成任务继续调用原 CLI。引擎按单作业串行启动，Spark 固定 `local[1]`，CLI 内存参数限制在 512MB–1GB。

版本依据为 [Spark 3.5.6 官方说明](https://spark.apache.org/docs/3.5.6/) 和 [Flink 1.20 Java 兼容说明](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/java_compatibility/)。Spark 支持 Java 17 与 ARM64；Flink 1.20 的 Java 17 仍有实验性及连接器范围限制，保留官方镜像默认模块开放参数。本目录的 SQL Client 错误传播入口继续生效，SQL 失败不会以 CLI 返回 0 冒充成功。

## 正在运行的 Worker

先确认没有活动引擎，再执行：

```sh
python3 deploy/dolphinscheduler-local/prepare_native_runtime.py
```

它通过 `docker cp/exec` 加载 root 监督器和受管入口，不重启 DS。TLS 文件生成在被 Git 忽略的 `runtime/native-tls/`，宿主客户端私钥权限为 `0600`，容器服务端私钥为 `root:root 0600`。无凭据连接路径清单写到 `/tmp/datahub-native-runtime-connection.json`。该部署不修改 DS 内存 H2 元数据。

HTTPS 只监听容器环回地址。宿主的纯 TCP 转发器监听 `127.0.0.1:19101`，将 TLS 字节送入现有容器；TLS 始终在容器 root 进程终止。转发器不持有服务端私钥，不开放 Docker socket，不对外监听。Java 使用本地 Spring SSL bundle 配置 CA、客户端证书与客户端私钥。

## 查询契约

```text
GET https://127.0.0.1:19101/v1/executions?name=dh_native_<recordId>&user=datahub
```

返回 `{"executions": [...]}`。单个元素仅有 `name`、`user`、`engine`、`state`、`exitCode`、`started`、`finished`、`processTreeAlive`，不返回原始日志、命令、SQL 或 diagnostics。查询必须通过 mTLS；名称与用户精确匹配。空数组、`UNKNOWN` 或尚未开始的记录都不能证明运行完成。

每次运行由 root 独立 supervisor 调用固定 CLI，并将引擎身份降为 `datahub`。Unix socket 通过 `SO_PEERCRED` 只允许该执行用户；只接受 SQL 文件及有限参数。SQL 文件先核对用户归属并使用 `O_NOFOLLOW`/打开后的 `fstat` 重新验证，随后复制为 root 管理的只读输入。Flink 的 `-i` 与 `-f` 文件共同绑定唯一 `pipeline.name`；Spark 绑定精确 `--name`。不同名称、任意 JAR/Java 参数、YARN master 等都会被拒绝。

Spark 同时接受 DolphinScheduler 3.4.2 生成的五个资源 `--conf`：driver/executor cores 和 executor instances 只能为 `1`，driver/executor memory 只能为 `512m`、`768m`、`1024m` 或 `1g`。其他 Spark 配置仍逐项白名单校验，不允许通过 extraJavaOptions、JAR 或部署配置绕过本地执行预算。

Supervisor 使用 Linux child subreaper 回收整个后代树。CLI 主进程退出后仍有后代时继续保持运行；只有所有后代已回收才写完成标记。停止入口或 socket 断开会触发整棵子树的 TERM/KILL，结果为 `CANCELLED`。root 独占的账本与输出位于：

```text
/var/lib/datahub-native-runtime/dh_native_<recordId>/manifest.json
/var/lib/datahub-native-runtime/dh_native_<recordId>/state.json
/var/lib/datahub-native-runtime/dh_native_<recordId>/output.log
/var/lib/datahub-native-runtime/rejections/dh_native_<recordId>.jsonl
```

状态通过临时文件、`fsync` 和原子替换写入；结果和日志权限为 `0600`，引擎不能修改结果。日志仍透传到真实 CLI 标准输出供 DS 采集。结果保留供验收，当前不自动清理；容器被删除前应留存必要结果。若需要长期部署，应使用外部 DS 元数据库，并将结果目录作为独立持久卷。

启动前的拒绝单独保存在 root 独占的 `rejections` 目录，包含时间、执行标识、可信 socket UID、固定原因码与请求摘要，不包含 SQL、原始参数或日志。它表示请求未被接受；不会创建执行终态，也不会出现在 HTTPS executions 列表中。人工核查可以结合该证据及 DS 实例失败信息处理未启动记录。对升级前没有拒绝审计的历史记录，只能明确标注参数策略重放与既有 DS 日志，不能补造当时的执行状态。

## 可重复构建

当前容器未重建。为后续新环境提供增量镜像和 Compose overlay：

```sh
docker compose -f deploy/dolphinscheduler-local/compose.yaml \
  -f deploy/dolphinscheduler-local/compose.native.yaml build
docker compose -f deploy/dolphinscheduler-local/compose.yaml \
  -f deploy/dolphinscheduler-local/compose.native.yaml up -d
```

应用 overlay 会重建 DS 容器，现有 H2 元数据不会保留；只能在安排好重新准备 DS 对象后执行。监督结果使用独立 `native-state` 卷。宿主环回转发器仍由准备脚本启动。

## 验收

```sh
python3 deploy/dolphinscheduler-local/native-runtime/test_runtime_contract.py
python3 deploy/dolphinscheduler-local/native_runtime_smoke.py
python3 deploy/dolphinscheduler-local/native_runtime_smoke.py --checks stream
```

真实烟测依次验证 Spark 批计算的 stdout、Flink 批写入 print sink、错误 SQL 失败、持续流实际输出以及停止后的整个进程树退出；报告只保存状态、标记存在性和输出摘要。请与平台端原生任务验收串行运行，避免争用单作业执行预算。仅查询到 SUCCESS 或仅收到停止请求返回值都不构成这里的验收通过。
