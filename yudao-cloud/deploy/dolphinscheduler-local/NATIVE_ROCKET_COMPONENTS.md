# RocketMQ 原生持续组件

`read-rocketmq`、`write-rocketmq` 使用官方 RocketMQ 5.3.2 Client/ACL SDK 和 Flink 标准 JSON format。独立受管源按已批准的主题与任务专属消费组拉取消息，输出逐条同步发送并要求 `SEND_OK`。构建成功不代表实际执行验收通过，当前运行结果另见验收 JSON。

## 安装与资源范围

```bash
python3 deploy/dolphinscheduler-local/prepare_rocket_native.py \
  --output deploy/dolphinscheduler-local/runtime/rocket-native/manifest.json --install
```

脚本以工作节点 Flink API 编译 Java17 代码，并重定位官方 SDK 依赖。只在没有原生 Flink 作业运行时安装。正式目标是当前工作节点 Flink1.20.3，不据此宣称任意版本兼容。

节点保存 `databaseId`、`topic`、`groupId`、显式 `columns` 和非秘密 `config`。输入还可保存 `offsetReset=earliest|latest`，仅无已保存位点时生效；输入配置支持 `pullTimeoutMillis`，输出配置支持 `sendTimeoutMillis`、`batchSize=1`。输出 `columns.key` 和 `fieldMappings` 必须一致，支持源字段重命名。输入只接受顶层同名 JSON 属性，不自动推断嵌套字段。消息上限 1MiB，Decimal 精度和日期语义明确校验；整数分数、溢出、重复属性、缺失属性等使作业失败。

管理员在工作进程配置 `DATAHUB_ETL_SECRET_DIR` 和 `DATAHUB_ETL_SECRET_REFS`，引用文件必须且只能有八个 Java Properties 键：

```properties
nameservers=管理员批准的NameServer列表，分号分隔且规范化小写排序
sourceTopics=批准输入主题，逗号分隔
sinkTopics=批准输出主题，逗号分隔
consumerGroups=批准消费组，逗号分隔
producerGroups=批准生产组，逗号分隔
useTls=true
accessKey=管理员提供
secretKey=管理员提供
```

秘密只在工作端打开连接时读取，不进入 SQL、Source、Sink、JobGraph 或检查点。运行时再次验证 NameServer、主题、组、方向和 TLS 与引用范围一致。

`IntegrationRocketBindingSupport` 另验证平台 `ROCKETMQ` 连接类型、规范化 NameServer、ACL 模式、TLS、当前数据库对象权限、任务编号、工作组、引擎和秘密授权。`dataforge.integration.rocketmq.bindings` 默认为空，安装声明默认为 false；配置模型为 `IntegrationRocketBindingsProperties`。读取消费组用 `consumerGroupTaskIds` 明确绑定唯一任务，跨绑定冲突拒绝。同任务流内重复的同主题/组源也会拒绝。

## 检查点与恢复

正式实时 Flink 参数启用 60000ms 检查点，未启用时源拒绝启动。每个队列的下一位置保存在 Flink operator state，只有检查点完成后才写入官方 Broker OffsetStore。恢复时严格校验资源标识与完整队列拓扑；位点失效、队列集合变化不会隐式跳过消息。输出失败或跨系统恢复仍可能重复，不宣称跨系统事务或 exactly-once。消费组属于一个平台任务，不提供多个任意任务共享组的负载分配。

## TLS 边界

官方 5.3.2 Netty 客户端未向 `SslHandler` 传 peerHost。为避免误把证书链验证称为主机名验证，TLS 只接受管理员钉住的有效终端证书集合，拒绝通用 CA 信任锚、测试模式和可覆盖的 `tls.properties`。使用工作进程 JVM 属性 `tls.test.mode.enable=false`、`tls.client.authServer=true`、`tls.client.trustCertPath=管理员只读终端证书文件`。不接受客户端私钥密码配置，避免官方日志输出该值。证书轮换需管理员更新钉住集合并验收。隔离 fixture 用私有 Docker 网络内的 ACL 明文连接；它不构成 TLS 握手或证书轮换验收。

## 隔离复验

```bash
python3 deploy/dolphinscheduler-local/run_maven_locked.py -q \
  -pl :yudao-module-dataforge-server -am test \
  -Dtest=EtlNativeRocketCompilerTest,EtlNativeMessageSchemaTest,IntegrationRocketBindingSupportTest,IntegrationNativeConnectorCompilationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
python3 deploy/dolphinscheduler-local/prepare_rocket_fixture.py
docker compose -p datahub-ds-local -f deploy/dolphinscheduler-local/compose.rocket-native.yaml up -d
python3 deploy/dolphinscheduler-local/initialize_rocket_fixture.py
python3 deploy/dolphinscheduler-local/native_rocket_smoke.py
docker compose -p datahub-ds-local -f deploy/dolphinscheduler-local/compose.rocket-native.yaml stop
```

fixture 固定官方镜像摘要，不发布宿主机端口，不接触平台现有 MQ。随机应用/管理员秘密只写忽略目录；管理员引用不复制到工作节点。默认禁止创建主题和消费组，由官方管理工具以挂载文件中的独立管理员身份预建明确资源。验收覆盖初始与启动后消息、Unicode 字段映射、超安全整数、完成检查点后的 Broker 位点与重启、真实失败关闭，以及目标 Flink 序列化器中的秘密哨兵检查。CLI 验收与管理 API/DS 调度实例验收分开记录。

官方依据：[拉取消费语义](https://rocketmq.apache.org/docs/4.x/consumer/03pull/)、[ACL1](https://rocketmq.apache.org/docs/bestPractice/06access/)、[5.3.2 官方源码](https://github.com/apache/rocketmq/tree/rocketmq-all-5.3.2)。
