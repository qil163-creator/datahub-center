# 批量文件入库

入口为管理端 `/dataforge/batch-file`，页面属于数据接入。任务配置在平台数据研发范围可见，接口分别校验 query、create、update、delete、execute 权限。未绑定上传素材仅上传者可使用或删除，系统文件另外校验已有文件管理的 query 与 download 权限。

四步配置依次为基本信息、上传文件、结构确认、目标表配置。保存仅登记配置与素材绑定，不连接目标执行 DDL 或写入。下线且不存在活动实例时允许编辑；上线后可执行一次。负责人必须是有效用户且已维护联系电话。用户提交的电话、存储地址、连接凭据和原始 SQL 均不作为配置输入。

文件范围为 CSV、XLS、XLSX，第一行表头、从第二行开始读取数据，Excel 使用第一张工作表。技术解析复用 biz-etl 的 TabularFileReader；文件持久化复用 infra ManagedFileApi。上传限制为单文件 20 MiB、每任务最多 20 个文件、总计 100 MiB；每文件最多 100000 行、256 列，单元格最多 1 MiB，解析组件另设总体展开预算。原文件作为不可变受管对象保留，执行时重新读取并校验 SHA-256，不把原始行数据写入平台任务 JSON。

字段默认 string，可删除或改名，名称不可空、重复、包含首尾空白或控制字符，允许中文。合并文件必须字段名、类型、顺序完全一致。每个文件必须且只能属于一个目标表；目标表名为字母或下划线起始的最多 64 位字母数字下划线。目标内文件顺序按配置顺序执行。

当前目标仅支持启用的 MySQL。不存在目标时使用 DB Switch 生成 InnoDB 建表 DDL；已有目标严格检查列名、顺序、类型、可空性与自增/生成列，符合时追加。不会执行 DROP 或 TRUNCATE，不自动跳过曾成功写入的行，重新执行会再次追加全部文件。普通数据表之外的对象拒绝写入。执行期间以持久执行人复核用户有效性、执行权限及目标 CREATE/INSERT 操作授权。

目标写入复用治理 `SafetySchemaScriptApi` 正式授权通道，执行人除 `dataforge:batch-file:execute` 外必须具备独立的 `governance:safety-script:execute` 权限，由现有系统管理的角色/菜单权限分配，不因创建或上线任务自动授予。目标存在任何适用的禁止策略时拒绝写入；配置修订变化也拒绝使用旧计划。CREATE 使用受管模式 DDL 入口，INSERT 使用显式列与参数 VALUES 的窄入口，执行严格采用返回 SQL 和参数顺序。前端通过业务文案说明写入权限，权限码与 RPC 细节仅属于部署管理配置。

| 配置类型 | 新建目标类型 / 数值限制 |
| --- | --- |
| string | MEDIUMTEXT，空串按空值 |
| bigint | 有符号 BIGINT |
| int | 有符号 INT |
| decimal | DECIMAL(38,18)，以 BigDecimal 转换，不经 double |
| double | DOUBLE，拒绝 NaN/Infinity |
| date | DATE，ISO 日期，年份 1000–9999 |
| datetime | DATETIME(6)，ISO 日期时间或以空格分隔，最多微秒精度 |
| boolean | TINYINT，接受 true/false/1/0 |

执行实例使用 UUID 请求键持久幂等，同一键绑定原任务与执行人。任务行锁与部分唯一索引限制同时只有一个活动实例。固定 2 个工作线程、16 个排队槽；容量不足时实例仍持久 QUEUED，由 XXL 作业补派发。执行者先 CAS 领取两分钟租约，每 20 秒续租，并在每个外部步骤/写入批次前复核围栏。所有目标写入都在平台锁事务以外。

每个文件先完成全部类型转换再写入，每批最多 500 行，并同时限制 5000 个参数和 25000 字符 SQL，宽表会缩小批次。DB Switch 生成参数化 INSERT，并通过 executePlan 为每批独占事务；只有 COMMITTED 与精确更新行数同时确认才记录本批写入量。新建表固定 InnoDB；既有表的存储引擎尚无公共元数据证据，所以失败后的 ROLLED_BACK 也保守按 UNKNOWN 处理，不假定非事务表已回滚。建表为 MySQL 独立 DDL，不能由后续数据批事务回滚。

已确认在写入前失败的文件继续下一个文件；混合成功失败标记 PARTIAL_SUCCESS。数据库操作发生后未收到可靠完成返回的文件标记 UNKNOWN；同一目标后续文件不执行，其他目标继续。实例或进程中断后过期 RUNNING 转 UNKNOWN，绝不自动重放。人工核查须确认外部工作者已停止、目标结果已检查，并填写原因；记录 resolvedBy/resolvedTime/resolutionReason，保留 UNKNOWN 历史且将任务下线、解除活动占用。

XXL handler 为 `batchFileRecoveryJob`，职责包括 QUEUED 派发、过期运行转 UNKNOWN、未绑定素材清理。未绑定素材 24 小时过期。移出配置或任务删除后的素材在没有近 30 天执行引用、没有未解决实例引用且最后更新时间超过 30 天时回收；当前任务仍引用的素材不会回收。删除请求会保留 CLEANUP_PENDING 意图，只有 infra 返回物理删除成功才标记 CLEANED，即使 infra 已返回 DELETED 墓碑仍继续重试清理。任务与实例审计元数据保留，不依赖原文件才能查看历史字段与结果。

部署须配置现有 RPC mTLS 客户端 SSL bundle 与 infra 受管文件服务信任，数据接入端不得直接使用公共文件 URL。缺少有效受管通道时上传失败关闭，不回退到公共文件上传。执行人与目标数据源的密码在运行期间现取现解密，任务/实例 JSON、浏览器响应与运行错误只包含业务配置或脱敏说明。

DataForge 使用 `yudao.rpc.client.ssl-bundle` 引用已配置的 Spring SSL bundle，infra 使用 `yudao.rpc.peers` 将客户端证书身份绑定到调用服务。发现服务的地址必须为 HTTPS，或通过 `spring.cloud.openfeign.client.config.managedFileApi.url` 配置受信任的 HTTPS 服务地址。部署时同时配置 `batchFileRecoveryJob`（dataforge-server）和 `managedFileCleanupJob`（infra-server），建议每分钟执行；后者持续扫描删除墓碑并清理删除请求之后才落盘的对象。两个作业均按单平台运行。

实现依据为 qData 已认证浏览器页面、REA 导出的三个页面脚本及用户提供的业务截图。原站使用四步配置、同结构合并与 MySQL 目标，保存会生成文件读取和写入节点。浏览器证据不能证明原站服务端 `writeModeType=2` 的具体语义，因此本项目明确采用上述创建或追加策略。文件大小上限按现有有界存储契约设为 20 MiB，与原站的 500 MB 上限不同。组件检索确认可复用 DB Switch 的产品元数据、DDL、脚本执行器，以及现有文件连接解析、执行权限和 XXL-Job。只读治理 API 不支持 CREATE/INSERT，批量 writer 也不能承载返回授权计划，故补充公共参数化 INSERT 生成和正式 INSERT-only 授权入口，再复用 executePlan；CSV/Excel 解析从已有 ETL reader 提取为公共能力，受管文件回执补充在 infra API，业务层负责配置、身份和持久运行编排。

