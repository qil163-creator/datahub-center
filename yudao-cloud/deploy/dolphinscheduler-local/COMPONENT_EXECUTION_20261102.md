# 49组件执行接入与验收记录（2026-11-02）

49类组件已有适用引擎下的执行路径与逐项支持合同。Spark脚本已通过正式平台任务，Flink Java脚本通过有界/窗口/恢复/取消真实验收；Flink独立Token初始化因没有受支持的下游认证消费路径，继续明确拒绝。ODPS云端验收仍为OPEN。

## 原站分析与能力判定

REA 重新捕获了原站49份组件表单及离线/实时页面资源，URL与SHA见 [reference.json](verification/components-20261102-reference.json)。静态表单和按钮颜色只能证明UI契约，不能证明原站服务端执行或加密字节格式。本次没有向原站保存或运行任务。

设计器由后端 `component-capabilities` 按引擎和模式返回能力，未知、加载失败、上下文过期时关闭新增入口。具体资源和参数仍由正式流程检查判断。“Spark/Flink”表示适用模式内存在执行路径，不表示全部组件适用于无限流。无窗口全局排序、Join、去重、行转列继续明确拒绝。

[实际API矩阵](verification/components-20261102-capability-matrix.json)覆盖6个引擎/模式上下文，每个上下文恰好49项。Spark离线有42项可配置，Flink离线26项、实时27项；Spark/Flink的适用路径合计覆盖49类。“部分支持”包含必须先配置的资源、物理字段、预算和窗口合同；不等于该组件已获得当前任务的任意数据访问权限。

## 逐项合同

| 组件 | 执行路径 | 关键合同 |
|---|---|---|
| 表输入 `read-db` | Spark/Flink | 受管Catalog；实时必须是真持续源，普通JDBC全量不能冒充持续采集 |
| ODPS输入 `read-odps` | Spark有界 | 官方SDK/Tunnel协议；完整表结构，云端验收OPEN |
| Excel输入 `read-excel` | Spark有界 | XLS/XLSX、sheet/范围、公式原文或缓存值、路径及展开预算 |
| CSV输入 `read-csv` | Spark有界 | 显式字段、编码、严格格式；区分空字符串与NULL |
| JSONL输入 `read-json` | Spark有界 | 顶层标量字段、严格类型，坏行失败 |
| Kafka输入 `read-kafka` | Flink有界/实时 | 显式无凭据绑定、主题与位点；持续和启动后消息；SASL/TLS尚未适配 |
| Hive输入 `read-hive` | Spark有界 | Hive4.1普通外部TEXTFILE，元数据复用DB Switch |
| HDFS输入 `read-hdfs` | Spark有界 | 授权根目录内CSV/文本，编码及压缩；当前仅显式NONE绑定，Kerberos尚未适配 |
| API输入 `rest-api` | Spark有界 | GET/POST、分页、严格JSON路径/形状/类型、秘密或Token引用 |
| MongoDB输入 `read-mongodb` | Spark有界 | 显式扁平字段、查询、受管集合；当前仅显式匿名绑定，认证秘密引用尚未适配 |
| Redis输入 `read-redis` | Spark有界 | Redis7+ string/hash/list；SCAN不提供快照 |
| RabbitMQ输入 `read-rabbitmq` | Flink实时 | JSON持续消费，checkpoint后确认 |
| TDengine输入 `read-tdengine` | Flink实时 | TDengine3.3.8.x TMQ、真实类型、checkpoint恢复 |
| RocketMQ输入 `read-rocketmq` | Flink实时 | 独立组、授权NameServer/Broker、checkpoint位点恢复 |
| FTP输入 `read-ftp` | Spark有界 | 专用chroot账户、CSV/文本、编码/gzip、秘密引用 |
| WebService输入 `read-webservice` | Spark有界 | WSDL1.1、SOAP1.1/1.2 literal、HTTP GET/POST、显式字段 |
| Java脚本 `java-script` | Spark有界；Flink有界/显式窗口 | 隔离Docker worker、显式批次；没有隐藏本地ETL回退 |
| Token认证 `custom-token` | Spark有界 | Token仅留broker私有上下文，下游API持引用 |
| 转换 `data-clean` | Spark/Flink | LOCAL字段删除、TRIM/大小写；有界去重 |
| Join `multi-join` | Spark/Flink有界 | 2–8路INNER/LEFT，显式输出行数预算 |
| 排序 `sort-records` | Spark/Flink有界 | 输出/脚本物化边界前排序；会丢序的中间转换明确拒绝 |
| 字段拆分 `field-split` | Spark/Flink | 字面/Java正则、包裹符、类型/精度/日期转换 |
| 字段派生 `field-derive` | Spark/Flink | 七种操作；表达式限声明的安全语义 |
| 去重 `dedup-records` | Spark/Flink有界 | 整行/按键、大小写策略；确定保留首条需显式全序 |
| 行转列 `row-to-column` | Spark/Flink有界 | 显式全序及唯一性检查，同组同键取末值 |
| 列转行 `column-to-row` | Spark/Flink | 同组字段类型一致 |
| 加密 `data-encrypt` | Spark/Flink | 摘要/Base64及六种受管密钥算法，不接受内联密钥 |
| 解密 `data-decrypt` | Spark/Flink | 六种受管算法/Base64；摘要不可逆 |
| ID生成 `id-generator` | Spark/Flink | UUID；有界SEQUENCE需全序、唯一性及溢出检查 |
| 计算器 `calculator` | Spark/Flink | 24种运算、Decimal边界、前序规则引用 |
| 值映射 `value-map` | Spark/Flink | 输出类型明确，保留原值策略受类型约束 |
| 常量 `add-constant` | Spark/Flink | 声明类型、精确数值与日期解析 |
| 数值范围 `number-range` | Spark/Flink | 数值输入、精确边界、字符串输出 |
| 字符串操作 `string-operation` | Spark/Flink | Unicode去空白、大小写及填充 |
| 字符串替换 `string-replace` | Spark/Flink | 字面/正则、整词、大小写、字段替换；受管函数 |
| 字段选择/修改 `column-map` | Spark/Flink | 显式映射、原生转换，Decimal最多38位 |
| 设置字段值 `set-field-value` | Spark/Flink | 来源使用原输入字段，不错误串行引用同层结果 |
| 条件分发 `data-condition` | Spark/Flink | IF/ELSE；ERROR必须有ELSE，每个分支接输出 |
| 子流程 `sub-pipeline` | Spark/Flink | 有权限的已发布固定版本、具名端口、当前引擎校验 |
| 表输出 `insert-db` | Spark/Flink | 已有受管目标表追加，不接受任意前后置SQL |
| ODPS输出 `write-odps` | Spark有界 | 所有分区block成功后统一commit；云端验收OPEN |
| Hive输出 `write-hive` | Spark有界 | 普通外部文本表追加；分区/ACID/视图拒绝 |
| HDFS输出 `write-hdfs` | Spark有界 | 暂存后逐文件发布，已有文件不覆盖；当前仅显式NONE绑定，Kerberos尚未适配 |
| MongoDB输出 `write-mongodb` | Spark有界 | insert、键更新、replace-upsert；当前仅显式匿名绑定，认证秘密引用尚未适配 |
| Redis输出 `write-redis` | Spark有界 | 授权键范围；list追加至少一次 |
| RabbitMQ输出 `write-rabbitmq` | Flink实时 | 发布确认；至少一次语义 |
| Kafka输出 `write-kafka` | Flink有界/实时 | 原生JSON写入，当前仅显式无凭据绑定；SASL/TLS尚未适配 |
| RocketMQ输出 `write-rocketmq` | Flink实时 | 同步SEND_OK、至少一次；TLS另有终端证书钉住限制 |
| FTP输出 `write-ftp` | Spark有界 | 完整源目标映射，STOU新文件，既有文件不覆盖 |

## 实际执行证据

- [最终发布版本正式任务](verification/components-20261102-platform-script-final-version.json)：完整模式/引擎配置、节点与连线顺序进入版本规则后，Task1004正常修改脚本源码、保存并发布，稳定DS实例7、记录180829751977019 SUCCESS；FTP累计6数据行及3份表头逐项精确一致，保留前两次实例4/6历史证据。

- [Flink Java脚本](verification/components-20261102-native-script-flink.json)：14个真实场景，原qData接口、精确类型、条数/时间窗口、保存点及批次缓存恢复、取消、实际缩放/状态范围等负例；只记原生执行范围，不冒充DS实例验收。

- [真实HTTP取Token脚本任务](verification/components-20261102-platform-script-hutool-token.json)：同Task1004正常修改源码并重新发布，Hutool经隔离IPC调用登录接口、私有Token认证下游API，稳定DS实例6 SUCCESS，追加目标完整核对。

- [新增转换/控制原生验证](verification/components-20261102-native-control-transform.json)：两引擎各一组合正例及一个歧义排序负例；12行精确数据和实际输出行序一致，包含固定版本子流程、NULL/完整重复去重、大小写保留原串、列转行与末端排序。

- [Spark脚本正式任务](verification/components-20261102-platform-script-token.json)：Task1004、稳定DS实例4，验证Token认证、排序后的整批Java输入、精确小数汇总、删行/追加和真实FTP目标。

- [平台HTTP/加密/FTP](verification/components-20261102-platform-http-crypto-ftp.json)：Task1002/1003从检查、保存、上线、执行到DS实例4/5 SUCCESS，核对完整CSV及中文目标字段，记录实际初始化JAR SHA。重启后再次核对远端终态。
- [转换](verification/components-20261102-transforms.json)：两引擎45列表达式、序列/行转列、重复排序键原生失败。Join/条件分流复用 [graphs.json](verification/graphs.json) 和 [join-budget.json](verification/join-budget.json) 的既有两引擎值证据。
- [CSV/JSONL](verification/components-20261102-files.json)、[Excel](verification/components-20261102-excel.json)：实际Spark值、类型、坏行/工作表/路径/预算失败。
- [Kafka](verification/native-kafka-components-20261102.json)、[MongoDB](verification/native-mongo-components-20261102.json)、[HDFS](verification/native-hdfs-components-20261102.json)、[Hive](verification/components-20261102-hive.json)、[Redis](verification/native-redis-components-20261102.json)：真实Docker服务和原生SDK/引擎值证据。
- [RabbitMQ](verification/components-20261102-rabbitmq.json)、[RocketMQ](verification/components-20261102-rocketmq.json)、[TDengine](verification/native-tdengine-components-20261102.json)：持续消息、确认/检查点恢复、失败关闭及认证值序列化扫描。
- [REST](verification/components-20261102-http.json)、[SOAP](verification/components-20261102-webservice.json)、[FTP](verification/components-20261102-ftp.json)、[gzip FTP](verification/components-20261102-ftp-gzip.json)、[加密](verification/components-20261102-crypto.json)：单独报告为原生CLI验证，不冒充平台调度验收。
- [ODPS](verification/native-odps-wire-20261102.json)：官方SDK的Tunnel wire/protobuf/CRC、block提交、精确值及失败不提交。**MaxCompute云端真实读写仍OPEN**；Docker wire夹具不是云服务。
- [实际表单](verification/components-20261102-ui-default-payloads.json)、[JSONB重排](verification/native-connector-ui-jsonb-20261102.json)：消费真实默认/隐藏字段及中文改名。对象键重排不改变任务摘要，数组与物理字段保序。
- [前端门禁](verification/components-20261102-frontend-quality.json)：官方Node24.16.0，包含边界、lint、类型、测试、生产构建；最新含去重功能的一轮429个测试文件、3751项全通过，类型检查与生产构建完成。
- [ETL全量组件测试](verification/components-20261102-etl-tests.json)：92类、458项中444项执行通过；该轮未提供外部夹具而跳过的14项，另以显式Docker和PostgreSQL夹具复核，不计为该轮执行通过。
- [Docker脚本复核](verification/components-20261102-script-docker8.json)：固定镜像摘要、真实隔离/配额/取消/宿主崩溃恢复8项全部通过，无跳过。Docker Desktop的sysfs占位接口保留作诊断，隔离判定使用实际网络命名空间、路由、连接与Docker网络模式；生产隔离策略未放宽。
- [平台消费者/DS合同](verification/components-20261102-consumer-tests.json)：25类、155项中152项执行通过，该轮3项PostgreSQL依赖测试单独复核；包含正式API检查、Schema、发布身份、JSONB摘要、实际默认表单和官方DS插件参数。
- [PostgreSQL独立复核](verification/components-20261102-postgresql-contracts.json)：全新Docker PostgreSQL16.15、TLS客户端证书认证，10项真实执行全部通过且无跳过；原9个跳过槽中有1项参数化展开为2项。覆盖精确读写、元数据、JSONB保序、修订CAS及事务回滚，测试容器已停止，未触及既有业务库。
- [后端质量门禁](verification/components-20261102-backend-quality.json)：DB Switch架构/清单、迁移编号及头部、错误码、模块依赖检查通过。
- [独立交付审计](verification/components-20261102-delivery-audit.json)：组件编码、294格引擎/模式状态、证据链接及公开产物秘密扫描；实际检查范围与限制见报告，不替代原生运行证据。

## 配置与复现

依赖以 `prepare_etl_functions.py`、`prepare_etl_excel.py` 和各 `prepare_*_native.py` 生成的SHA绑定为准。Java17执行依赖与平台JDK25输出隔离；连接器的Hutool及内部辅助类用私有包，避免多组件覆盖方法签名。不可变JAR复用摘要匹配的已有文件，不在作业运行时改写。

管理员绑定任务/工作组/引擎范围的资源、表、路径及秘密引用。节点不携带原密钥；秘密目录和引用白名单默认关闭。保留任务RBAC、数据源授权、只读源SQL、物理绑定、参数校验及新文件无覆盖约束。

隔离DS为 `components-stable-ds`，API仅 `127.0.0.1:12347`，全新H2卷及正常认证，定义/令牌经受控重启验证。私密配置位于ignored的 `runtime/components-isolated-ds.json`，不随代码交付。`start_components_platform.py` 持Maven锁复制不可变classpath，仅管理其记录的 `127.0.0.1:50026` 实例；不要复制运行中的H2文件。稳定DS、独立平台和本任务HTTP/FTP夹具保留供复核；其余探针/连接器测试容器已停止，未重启用户原有服务。

流程顺序：`POST /check` → `PUT /flow` → `PUT /status` → `POST /run` → `GET /records` → 读取外部目标核对。组合验收用 `platform_components_smoke.py --config <0600私密配置> --output <报告>`。仅提交成功或RUNNING不算验收成功。

## SQL执行顺序

本次组件实现没有新增平台迁移，本任务未执行平台迁移。前提是集成任务与DS记录表已按现有基线及 [0252_dataforge_integration-ds-runtime.sql](../../sql/postgresql/migrations/0252_dataforge_integration-ds-runtime.sql) 存在；已有环境无需为本次组件重复执行0252。引擎按“受管函数初始化 → 临时源/目标声明 → 原生INSERT/分支/Stage”执行生成SQL，目标遵守各连接器的追加或新建合同。
