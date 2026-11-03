# Redis 与 HDFS 原生组件

本文件对应 `EtlNativeRedisCompiler`、`EtlNativeHdfsCompiler` 和 `IntegrationFlowCompiler.prepareNative` 的实际消费链。两者均为 Spark 有界批处理，编译及字段分析使用同一绑定；不能把 SCAN 或文件全量读取标成持续流或持久增量游标。

## Redis

复用官方 `redis.clients:jedis:7.4.1`，在工作节点读取受管秘密后建立连接；Jedis 与其依赖在单独运行 JAR 中重定位。官方 Spark-Redis 的 DataFrame 模式只提供 hash/binary，因此这里用 Spark 数据源及 Hive 行函数适配原生 string/hash/list。读取使用 `EVAL_RO` 在响应返回客户端前检查原始字节预算，需要 Redis 7 或更高版本；本次真实测试版本为 Redis 8.2.3。

1. 运行 `prepare_etl_redis.py --output /tmp/datahub-etl-redis-binding.json --install`，按生成 manifest 将 `sparkInitializationSql` 配到 `dataforge.integration.spark-initialization-sql`，并将 `DH_READ_REDIS` 加入管理员 `managed-functions`。
2. 配置 `dataforge.integration.redis.bindings.<平台数据源编号>`：`allowed-task-ids`、`worker-groups`、`engines=[SPARK]`、`host`、`port`、`database`、`use-ssl`、`credential-ref`、`source-patterns`、`sink-prefixes`、`spark-connector-installed=true`。平台连接类型必须为 REDIS，主机、端口和数据库编号与此绑定一致。
3. 同一引用还须在 `dataforge.integration.managed-secret-bindings` 中同时授权任务、工作组和引擎。工作节点只挂载该环境批准的引用，并设置 `DATAHUB_ETL_SECRET_DIR` 和逗号分隔的 `DATAHUB_ETL_SECRET_REFS`。

引用对应 UTF-8 Properties 文件，恰好包含 `host/port/database/useSsl/sourcePatterns/sinkPrefixes/username/password` 八键；地址和范围须与编译后的计划一致。秘密值只能在执行环境挂载。源模式按完整字符串精确匹配，写入按非空前缀匹配；模式和前缀中的逗号不能用于 Properties 范围列表。

输入配置使用 `databaseId/dataType/keyPattern/columns`，可选择 `maxKeys/maxRows/maxBytes/scanCount`。`columns` 中的 `redisField`（前端保存为 `key`）支持 hash 字段名、string/list 的 JSON 属性路径及 `@key`；string/list 支持 `@value` 返回原始字符串，list 还支持从零开始的 `@index`。未适配参数、错误类型或超预算均明确失败；字段为标量，不会将对象静默字符串化。

输出使用明确目标字段合同和同序 `fieldMappings`，支持源字段到目标字段改名，同时消费 `batchSize` 和固定 Key 或 `result:${id}` 这样的模板。string 将完整行 JSON 写入 SET；hash 对声明字段执行 HSET，并对 NULL 字段执行 HDEL；list 追加行 JSON。JSON 中保留 NULL 与空字符串的区别。Key 模板引用映射后的目标字段。Key 字段不能为 NULL、空字符串或控制字符，最终 Key 仍须位于批准前缀。每批检查每条 Redis 响应，任一命令失败则任务失败。

SCAN 不是一致性快照。Redis 批次不是全作业事务；失败可能已执行部分命令，list 重试可能重复。这里不承诺回滚或恰好一次。TLS 的受管地址及主机名验证已实现，本地合成夹具使用隔离 Docker 网络和 ACL，本次没有 TLS 服务端验收。

运行 `prepare_redis_fixture.py` 后，先执行 `EtlNativeRedisCompilerTest` 生成正式编译 SQL，再运行 `native_redis_smoke.py`。它只操作隔离夹具中的 `input:*` 和 `result:*`；不暴露主机端口、不输出密码。三种数据结构各两行的 Long、Decimal、Date、4400 Unicode 字符、空字符串与 NULL 逐值验证，以及引用越权、分数整数和行预算三项拒绝见 [验收结果](verification/native-redis-components-20261102.json)。这份结果不代表本次通过管理端触发过任务。

## HDFS

使用任务授权的 `IntegrationDataxBindingsProperties` 资源，要求 `connectorCodes` 含对应 read/write-hdfs，插件标记含 `spark-hdfs`，并明确 `authentication=NONE`；Kerberos 环境需要单独的受管工作节点身份适配。`defaultFs` 是无凭据 HDFS 服务 URI，`rootPath` 是非根绝对路径，节点 `path` 是该根内的相对路径。

运行 `prepare_etl_hdfs.py` 构建 Java17 数据源。工作节点还须在 `DATAHUB_ETL_HDFS_ROOTS` 中用分号列出精确授权根 URI。驱动及分片写入都校验实际 HDFS 路径，无法借 URL、目录跳转、通配符或符号路径越界。`EtlSparkRowValues` 是 HDFS、Redis 和 FTP 共用的无损标量格式化器。

CSV 和分隔 text 消费编码、分隔符、类型、日期格式、文件名前缀和压缩配置，输出按 `columns.key` 和同序 `fieldMappings` 消费真实字段改名。默认 NULL 标记为 `\N`；非空标记保留了 CSV 的 quoted empty 与 NULL 的区别。字段值与 NULL 标记冲突、text 字段含分隔符/CR/LF 或空字符串时明确失败，避免不可逆行列错位。CSV 会正规转义引号、分隔符和换行。

输出只创建含前缀、作业 UUID 与分片编号的新文件，通过暂存目录发布并拒绝覆盖；不删除已有文件。提交中断可能已经发布部分文件，不声明全局回滚或跨作业恰好一次。gzip/bzip2 已真实验收；snappy/lzo 需要额外的工作节点原生编解码器标记，本次没有对应正向环境验收。

真实 Hadoop 3.4.1/Spark 3.5.6 的 UTF-8 CSV+gzip、GBK CSV、text+bzip2 正向值、现有文件哨兵及错误类型/未授权根/有歧义 text 三项拒绝见 [验收结果](verification/native-hdfs-components-20261102.json)。本地 HDFS/Hive 精确 self-proxy 只授权 `datahub` 用户从 `hive-native-server` 为自身调用，没有通配符授权。
