# Spark IO 高级参数执行差距（2026-11-03）

本次只读调查不扩大产品白名单：Spark 49、DataX 3、Flink 离线/实时各 12。表单控件存在不代表源站公开了执行协议，静态前端和现场只读观察不能证明其事务、重试、删除或提交语义。

| 项目 | 当前真实机制 | 尚缺部分 | 最小可行补齐路径 |
| --- | --- | --- | --- |
| ODPS 全量覆盖 | `EtlNativeOdpsCompiler` mode=1 核验资源 `allowOverwrite`；`EtlManagedOdps.upload` 将 overwrite 传给官方 TableTunnel；`EtlOdpsProvider` 在 driver 收齐分片后提交会话 | 不能把已实现覆盖误报为尚未实现；生产 MaxCompute 验收与本地 SDK 协议夹具分开记录 | 复用现上传会话，不新增写法；验证覆盖授权、提交应答丢失、任务 attempt 分片与字段合同 |
| ODPS SQL 输入 | 当前只提供精确授权表/分区的 Tunnel 全量读取；自定义 SQL、where 明确拒绝 | 官方 SQLTask 提交、实例身份、结果下载及取消没有适配；`EtlOdpsTransport` 仅允许固定 `/projects/<p>/tables/<t>` 路径 | 新增受管 SQLTask 行源：先限制到已授权单表 SELECT，再绑定本次创建的实例 ID，限时轮询，InstanceTunnel 有界下载，取消/超额停止实例。复用秘密引用、TLS/地址授权、无损类型及行/字节预算。不要直接放开整个 projects 路径 |
| ODPS 前后 SQL | 输出 Tunnel 会话能最后提交数据；SDK 429/5xx 已禁止隐式重试 | 无 SQLTask hooks；提交后 postSQL 失败不能撤销已提交表数据；应答丢失也不能假设未执行 | 在受管 worker 增加前置 SQLTask→Tunnel 写入提交→后置 SQLTask 生命周期，限定目标表/分区与语句类别；保存实例/阶段回执，UNKNOWN 禁止自动重放。不宣称跨步骤事务。共享凭据传输策略不能绕开 |
| Hive SQL 输入 | DB Switch `HiveNativeCatalog` 核实 Hive4.1 普通外部 TEXTFILE、排除 ACID/分区/视图；当前 Spark Catalog 读取支持已授权字段及 where | 独立 SQL 模式与字段解析没有接到实际原生 SQL 编译 | 第一阶段可复用 `RestrictedSelectGuard.restrictProjection` 的单表、精确字段、排序和谓词边界，用 AST 将唯一 FROM 关系绑定到管理员 Catalog。仅开放实际验证过的 SELECT 子集，元数据和执行用同一编译；JOIN/CTE/跨表/任意函数必须另取授权与方言证据 |
| Hive 全量覆盖/hooks | 当前原生 Catalog 输出仅 append | 无覆盖授权、前后 SQL worker 生命周期、写入失败/应答未知时的重试协议；不得把 pre/postSQL 拼接后假设原子回滚 | 保留 Spark 原生文件提交器；另建受管输出生命周期，在同一 worker 会话按 pre→native append/overwrite→post 顺序失败即停。覆盖先核对真实存储 URI/表形态、单表覆盖授权及并发写入约束。普通 TEXTFILE 不承诺事务；需要阻止未确认副作用的自动重放 |
| Hive batchSize | 原生 Catalog SQL 写文件由 Spark 管理批次，当前明确拒绝此参数 | 没有与“单次写入条数”等价的 native Catalog 设置 | 不得映射到无关的 Arrow/JDBC/Spark 配置。若切换为受管 JDBC sink，可复用已有 DB Switch `HiveWriterImpl`/`HiveJdbcWriteSupport` 的真实有界多 VALUES 及服务器确认；但其 `NonTransactionalWriteExecution` 的 UNKNOWN 不能自动重试，且不能悄悄替换现有文件提交语义。需要先确定表单参数的产品执行定义 |
| FTP 同步删除 | 已实现单文件/目录模式与真实路径类型检查。输出使用 STOU 创建唯一文件，Spark task commit 返回路径；driver commit 当前为空，abort 只清理本次新文件 | 没有“整个下游提交完成→源资源确认/删除”协议；参考站未取到精确保存键、tooltip 或删除对象语义 | 当前保持明确拒绝开启。先取参考语义；若确认为成功后删源，应生成受管根内固定文件清单/指纹，源读取只登记，driver 收齐所有输出提交回执后再验证未变化并确认删除。需文件数量/总大小预算、同根锁、重试与未知回执处理、跨输出失败测试。`deleteCreated` 只用于本次输出文件补偿，不能拿来删输入 |

可复用的实际组件：`EtlManagedSecrets`、`EtlOdpsTransport`、`EtlManagedOdps`、ODPS provider 的单会话分片提交、`RestrictedSelectGuard`、`HiveNativeCatalog`、DB Switch `HiveWriterImpl`/`HiveJdbcWriteSupport`/`NonTransactionalWriteExecution`、FTP chroot 与普通文件验证。复用前仍需保持各自授权、连接所有权、提交和 UNKNOWN 边界。

本轮已完成的实时 PostgreSQL upsert 不依赖上述缺口：另有官方 Flink JDBC 实际五场景证据与部署说明。FTP 删除语义未得到证实，本调查不猜测删除源或目标，也未执行任何删除开关。
