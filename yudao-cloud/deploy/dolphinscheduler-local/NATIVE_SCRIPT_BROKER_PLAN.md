# 原生引擎隔离脚本运行合同与验收

2026-11-02 已实现并真实执行。Spark 正式平台 task1004、独立 DolphinScheduler3.4.2、Spark3.5.6、mTLS broker、Docker worker 与 HTTP/FTP 夹具形成完整链路；不是本地 ETL 替代执行。Flink 的 Table/DataStream stage 与恢复/取消另见 [NATIVE_SCRIPT_FLINK.md](NATIVE_SCRIPT_FLINK.md)。Flink custom-token 仍未提供消费链路，编译阶段直接拒绝。

## 原站接口

本轮49文件取证位于 `/tmp/qdata-20261102-forms/`，原始 URL/SHA 见该目录 manifest。`java-Ba-9YCw3.js` 和 `customAuth-DlAYqY9M.js` 使用 `tech.qiantong.qdata.common.core.base.EtlBase`：

```java
String execute(com.alibaba.fastjson2.JSONObject data, Object logParams)
```

`data.data` 是完整当前批次的可变对象数组，可删除、修改、追加行及跨行计算。Java 返回 `1` 成功，`0` 或异常失败；认证脚本返回 token 字符串，`0` 失败。已有 `EtlJavaScript` 接口继续可用。Long、Decimal、日期经有界二进制协议传递，不经浮点 JSON 中转。输出逐行校验名称、声明类型、nullable 与精度。

## 原生 stage

- Spark 保留输入 SQL 的实际分区及分区内顺序，不追加 repartition/coalesce。`batchPolicy=PARTITION` 与 `maximumRows` 必须显式声明，完整分区超限整体失败，不隐式切小批。原生作业缓存已确认的 stage，多个输出不会暗中重跑用户脚本。
- Flink 通过真实 Table/DataStream API 接续 SQL、可信算子、SQL。流式必须显式选择 `COUNT_OR_TIME`、条数及时间边界。具体范围、检查点与至少一次 sink 边界见独立证据。
- `workerState=BATCH` 必须显式选择，每批创建独立 worker；不承诺跨批用户对象状态。历史 batchSize、冲突 classPath、非批模式 flush 参数不能静默吞掉。
- 原生 launcher 的版本化有界程序仅包含 SQL、字段、受管引用、源码 hash 和批次策略。用户源码只在平台同进程发布控制面登记，再传入隔离 worker；引擎 JVM 不编译或反射执行源码。

## 发布、身份与恢复

默认 `dataforge.integration.native-scripts.enabled=false`、资源/身份授权为空。管理员只批准任务、工作组、引擎、独立执行证书、资源和上限，不逐次手工批准源码 hash。正常保存发布会准备不可执行版本，DS 发布及数据库 CAS 成功提交后激活；失败/回滚删除准备版本。检查、字段分析不登记源码。版本包含规范化对象键的完整执行配置、模式、节点/连线数组顺序、输入槽和控制类型；JSONB 对象键重排不改变版本。

每个任务/工作组/引擎必须使用不同客户端证书，禁止共享证书承担多个执行身份。broker 同时核对证书指纹、任务/工作组/引擎、源码 hash、当前 epoch、绑定和运行实例。私钥、证书与 CA 在可信运行端按受管引用读取，不写 SQL、流程、DS 参数或 checkpoint。带点的 Spring 配置 Map 键使用 `[script1004.key]` 等括号保留原引用。

每个会话使用单调批次序号，完全相同请求返回已缓存结果，不同输入重放或未知副作用明确拒绝。ACK 清理已完成检查点之前的缓存，响应预算在全 broker 范围限制。活动 BATCH/ACK 续租，空闲有界过期；正常 token worker 结束后，私有认证上下文可供后续 API 使用。发布新版本后旧版本不能创建新 run，已有 run 的缓存可继续读取。

broker 重启会更换 epoch。旧程序必须正常重新保存发布，不能偷偷重新执行未知副作用。Docker 持久账本按容器、镜像、原始只读挂载和文件身份恢复，未确认终止保留容量。APFS 同 inode 的硬链接不按字符串路径误判；符号链接仍拒绝。

## Hutool HTTP 与私有 token

worker 始终 `network=none`。固定 URL handler 将原站 Hutool 的 http/https 请求经有界 IPC 交回 broker；端点、方法、字节和完整响应 deadline 由管理员资源约束，默认无请求权限，不存在直接网络回退。自动生成的 Content-Length 仅在与完整实体长度相符时由 handler 去掉，再由宿主 HTTP 客户端生成；UI 和普通 HTTP 配置仍禁止传入协议头。生产认证所需 headerSecretRefs 在可信 broker 注入，用户 worker 不读取 launcher 秘密目录。

worker HTTP 默认只授予 QDATA_TOKEN；普通数据 Java 需要管理员额外显式授予相应语言。认证返回值仅保存在 broker，原生作业取得不透明引用。`headerContextRefs` 使用 `header -> {tokenKey,prefix}`，只有直接上游初始化认证节点声明的键可消费。token 不成为输出列、SQL、流程参数或明文 checkpoint。返回体回显注入 token 时拒绝。`writeLog`、System.out/err 使用有界丢弃输出，Docker 日志驱动为 none，原始 stderr 也仅有界排空。

## 实际证据

- [统一 Spark/安全证据](verification/components-20261102-native-script.json)：正式 task1004 三条真实平台链、mTLS 正反例、隔离/日志与14份产物秘密扫描。
- [公开 nonce 计算链](verification/components-20261102-platform-script-token.json)：DS instance4，Token→认证API→上游排序数组断言→Java整批删/增/精确Decimal→FTP，2行精确一致。
- [真实 Hutool 登录链](verification/components-20261102-platform-script-hutool-token.json)：DS instance6，POST/login→取返回token→后续API认证→同一原生Java stage→FTP，追加后4行精确一致。
- [最终版本完整执行快照](verification/components-20261102-platform-script-final-version.json)：DS instance7 / record180829751977019，正常修改源码后重新保存发布，当前Mode/EngineConfig/输入槽/数组顺序版本规则生效；第三次成功追加后6行数据精确一致。实例4与6保留为独立历史证据，不能混用各自JAR版本。
- 实际 mTLS 拒绝：其他任务证书、错误任务/工作组/引擎/run、过期 epoch、未激活版本、同序号不同输入、新版本发布后旧版本新 run。完全相同缓存重放及原 run 恢复可用。
- 实际 Docker 检查：network none、只读、仅固定只读依赖、无秘密环境、日志驱动 none；跨行 Decimal/增删行完成，stdout/stderr/原始 FD 打印不持久化，关闭后 worker0。
- SQL/DS 参数、真实任务日志、平台日志、源码图、launcher/worker JAR（展开扫描）检查运行 token、API token、PEM/DER 私钥，UTF8/UTF16 三种文本编码均零命中。完整键材料不写公开证据。

公开 nonce 登录夹具证明传输与流程，不替代生产认证服务验收。Flink 证据独立列出，不用编译单测代替引擎验收，也不将未实现的实时 token 模式标为支持。
