# ODPS / MaxCompute 原生批处理组件

`EtlNativeOdpsCompiler` 将 `read-odps/write-odps` 编译为 Spark 3.5.6 的受管行源及 DataSource V2 输出，实际调用与现有 DB Switch ODPS 驱动一致的官方 `com.aliyun.odps:odps-sdk-core:0.52.3-public` TableTunnel。业务层只适配平台连接、任务授权和类型合同，不能把 ODPS 名称当作 Spark 本地表绕过认证或参数。

## 部署与授权

先运行 `prepare_etl_odps.py --output /tmp/datahub-etl-odps-binding.json --install`，按 manifest 配置管理员 `spark-initialization-sql` 和 `managed-functions=[DH_READ_ODPS]`。Java 17 运行包独立重定位 SDK、Hutool 及所有公共 core helpers，构建与部署均核验实际源快照和 SHA-256，不向其他组件暴露不兼容 helper 类。

`dataforge.integration.odps.bindings.<平台连接ID>` 需要明确 `allowed-task-ids/worker-groups/engines=[SPARK]`、`service-endpoint/tunnel-endpoint/project/credential-ref/spark-connector-installed`，以及每个资源的 `table/partition/columns/source/sink/allow-overwrite`。平台类型须为 ODPS，项目与平台连接的数据库名相同，serviceEndpoint 与 ConnectionSettings 的 endpoint 相同。`columns` 是完整有序的原生物理字段合同，不能只声明被选字段隐藏其余列。

同一 `credential-ref` 必须在 `dataforge.integration.managed-secret-bindings` 对任务、工作组、引擎全部授权。执行环境只挂载批准引用，配置 `DATAHUB_ETL_SECRET_DIR` 和 `DATAHUB_ETL_SECRET_REFS`。引用文件是 UTF-8 JSON，恰好含以下键：

```json
{
  "serviceEndpoint": "https://managed-service.example/api",
  "tunnelEndpoint": "https://managed-tunnel.example",
  "project": "managed_project",
  "accessId": "由执行环境挂载",
  "accessSecret": "由执行环境挂载",
  "securityToken": "可选临时会话令牌",
  "allowPlainHttp": false,
  "resources": [{"table":"managed_table","partition":"ds='20261102'","source":true,"sink":false,"allowOverwrite":false}]
}
```

Secret 的端点、项目及精确表分区/方向必须再次与计划一致。HTTP 只供显式 `allow-plain-http` 的隔离协议夹具，生产默认要求 HTTPS。传输固定两个批准端点和一个精确表路径，拒绝重定向、内联凭据和代理覆盖。SDK clone 保留受管 Transport，防止 Tunnel 默认克隆丢失边界。

## 执行合同

输入是明确的全量有界下载，支持字段投影、完整分区、最大行数和字节预算。先核查实际 SDK schema 的字段顺序、原生完整类型和全部分区键，再读取官方 protobuf/CRC 数据流。整数、Decimal、DATE、微秒时间戳、UTF-8 字符串、NULL 与空字符串按值处理；无法无损表达的值明确拒绝。DATE 使用 `LocalDate`，避免 JVM 时区与 SDK GMT 转换造成跨天。原生 FLOAT 先按真实二进制值精确扩展到 Spark DOUBLE，写回必须可无损收窄；不会将 `1.1f` 的真实值改写成 DOUBLE 的 `1.1`。

输出消费真实 `columns.key` 和同序 `fieldMappings`，支持源字段到目标字段改名；必须按真实表完整字段顺序声明目标。追加为默认，覆盖必须另有明确资源授权。Driver 创建唯一 UploadSession，分片只上传暂存 block，不携带密钥；关闭推测执行，分片数不超过 20000，全部成功后仅 Driver 最终 commit。`batchSize` 控制记录缓冲 flush。

ODPS SDK 0.52 对 429/5xx 的内层 Tunnel 重试不服从 socketRetries，传输边界将这些响应变成受控 IO 失败，禁止最终提交被暗中重放。失败后暂存块由官方会话 TTL 清理；最终提交应答丢失可能留下不确定状态，必须核查云端会话后再重试，不承诺全作业回滚或跨作业恰好一次。

当前独立 ODPS SQL、前后 SQL 钩子、持久增量游标、三层 Schema 表和复杂类型没有等价实现，配置这些能力会明确拒绝。

## 验收范围

`prepare_odps_wire_fixture.py --isolated-worker` 可同时创建与现有引擎相同镜像的独立只读 Spark 工作容器（只挂自己的 ODPS JAR 与 tmpfs），避免共享 DS 生命周期干扰；随后用 `native_odps_wire_smoke.py --worker datahub-ds-local-odps-spark-worker` 运行。夹具启动无主机端口的隔离 Docker 进程，使用同版本官方 SDK 的 protobuf writer/reader 和 CRC，校验真实 SDK Tunnel 路径、session、暂存块及最终提交请求。`native_odps_wire_smoke.py` 驱动真实 Spark 执行该适配器，逐值核对 Long、Decimal、日期、Unicode、empty/NULL 及既有行，验证 HTTP 403、schema 不匹配、CRC、最终提交失败和越权引用均明确失败。

这是 **官方 SDK wire 与真实 Spark 适配验收**，没有运行官方 MaxCompute 云端服务。云端真实 TableTunnel 读写、IAM、地域 TLS 端点和提交故障核验仍为 **OPEN**；不能把 Docker 协议夹具、Hive 或本地数据目录当成真实 MaxCompute 服务端验收。
