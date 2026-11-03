# RabbitMQ 原生集成组件

`read-rabbitmq` 和 `write-rabbitmq` 使用 Flink 持续表适配。协议读写委托 Apache 官方 `RMQSource`、`RMQSink`；行转换委托官方 JSON format，前置校验防止分数转整数、数值溢出、Decimal 隐式舍入和重复 JSON 键。

本次实际验证目标是 Flink 1.20.3 / Java17 / RabbitMQ 4.1.4。官方 RabbitMQ connector 3.0.1-1.17 只声明 1.16/1.17 支持；本实现对当前目标版本有真实验收，不能据此推断其他版本兼容。

## 工作节点安装

```bash
python3 deploy/dolphinscheduler-local/prepare_rabbit_native.py \
  --output deploy/dolphinscheduler-local/runtime/rabbit-native/manifest.json --install
```

构建过程使用目标工作节点提供的 Flink API，并重定位 AMQP 客户端；不会读取凭据。安装位置为 `/opt/flink/lib/datahub-managed-rabbit.jar`。该脚本应在没有原生任务运行时执行。

管理员为工作进程配置：

```text
DATAHUB_ETL_SECRET_DIR=/管理员挂载的只读目录
DATAHUB_ETL_SECRET_REFS=rabbitAccess
```

该目录下 `rabbitAccess` 是 Java Properties 文件，必须且只能包含下列八个键。秘密读取复用 `EtlManagedSecrets`，按引用白名单、文件类型及字节预算验证。连接只在运行端打开，实际密码不进入 SQL、Source/Sink 序列化或 JobGraph。

```properties
host=批准的RabbitMQ主机
port=5671
virtualHost=/批准的虚拟主机
sourceQueues=批准的输入队列
sinkQueues=批准的输出队列
useSsl=true
username=批准的账户
password=由管理员提供
```

文件会再次校验主机、端口、虚拟主机、TLS 和当前方向的队列范围。账号在 RabbitMQ 端也应有相应权限；使用默认交换机发布时需授权 `amq.default`。组件只被动检查已存在的队列，不创建队列。

## 平台授权

`IntegrationRabbitBindingSupport` 从平台数据连接读取 `RABBITMQ` 类型、主机、端口，并复用 `ConnectionSettings.read` 检查虚拟主机和 TLS；数据连接对象权限、任务、工作组、引擎、队列方向和秘密引用授权均必须满足。配置默认为空。

以下是配置结构示例，ID、工作组和账户引用必须换成实际批准的对象；不是可直接应用的业务授权：

```yaml
dataforge:
  integration:
    managed-secret-bindings:
      "[rabbitAccess]":
        allowed-task-ids: [9007199254740993]
        worker-groups: [批准的工作组]
        engines: [FLINK]
    rabbitmq:
      bindings:
        9007199254740994:
          allowed-task-ids: [9007199254740993]
          worker-groups: [批准的工作组]
          engines: [FLINK]
          host: 批准的RabbitMQ主机
          port: 5671
          virtual-host: /批准的虚拟主机
          use-ssl: true
          credential-ref: rabbitAccess
          source-queues: [批准的输入队列]
          sink-queues: [批准的输出队列]
          flink-connector-installed: false
```

仅在实际工作环境安装并验收后，将安装声明改为 `true`。平台连接的扩展配置必须使用 `schemaVersion: 1`，并与绑定的 `virtualHost`、`tls` 一致。

正式集成任务由 `NativeTaskParameters.FlinkOptions` 为实时模式生成 60000ms 检查点配置。输入在运行端拒绝未启用检查点的环境，避免降为自动确认。输入采用检查点确认；输出发送持久消息、要求可路由并等待服务端确认，恢复时仍可能重复，不提供跨系统事务语义。重启策略由运行环境配置：验收负例显式使用 `restart-strategy.type=none`，不能把该测试选项当成正式环境已经配置。

## 参数范围和复验

节点只保存平台 `databaseId`、队列 `topic`、字段声明和非秘密 `config`。输入读取顶层同名 JSON 字段；输出按 columns.key 和 fieldMappings 显式映射来源，可重命名 Unicode 字段；日期采用 ISO 格式，Decimal 精度必须明确；单条消息上限 1MiB。输入 `config` 仅支持 `prefetchCount`；输出仅支持 `confirmTimeoutMillis`，`batchSize` 只能为 1。无法等价处理的参数会拒绝，历史配置不会被默默忽略。

```bash
python3 deploy/dolphinscheduler-local/run_maven_locked.py -q \
  -pl :yudao-module-dataforge-server -am \
  -Dtest=EtlNativeRabbitCompilerTest,IntegrationRabbitBindingSupportTest,IntegrationNativeConnectorCompilationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
python3 deploy/dolphinscheduler-local/prepare_rabbit_fixture.py
docker compose -p datahub-ds-local -f deploy/dolphinscheduler-local/compose.rabbit-native.yaml up -d --wait
python3 deploy/dolphinscheduler-local/native_rabbit_smoke.py
docker compose -p datahub-ds-local -f deploy/dolphinscheduler-local/compose.rabbit-native.yaml stop
```

本机 fixture 的凭据随机生成，只保存到忽略目录和工作节点秘密目录；服务没有宿主机端口。证据见 [components-20261102-rabbitmq.json](verification/components-20261102-rabbitmq.json)：两条初始/启动后消息精确读写、保持运行后停止、三类真实 Flink 失败、七类类型边界及三种编码的序列化秘密检查。它验证官方引擎和服务链路；管理端 API 与 DS 调度实例验收单独记录。

官方依据：[RabbitMQ connector](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/connectors/datastream/rabbitmq/)、[官方版本兼容声明](https://flink.apache.org/downloads/)。
