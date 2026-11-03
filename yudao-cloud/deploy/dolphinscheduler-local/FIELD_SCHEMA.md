# 只读字段来源与结构分析

接口为 `POST /dataforge/integration/schema`，权限是 `dataforge:integration:query`，不依赖旧 ETL 页面权限。请求沿用 `{id, revision, flowJson}`，不会保存节点、更新任务状态、产生运行记录或向 DS 提交任务。

响应为 `{nodes:{节点编号:{inputFields,outputFields,complete}},errors,warnings}`。字段包含 `name`、`dataType`、`nullable`、`length`、`precision`、`scale`、`timezone`、`sourceName`。缺省或 null 表示未知；不把未知类型补成 STRING。字符长度只来自 DB Switch `characterLength` 事实，十进制总有效位数和小数位只来自 NUMERIC/DECIMAL 的 `numericPrecision/numericScale`，不会将 BIGINT 的二进制精度或 JDBC 显示宽度混入。长度为 Long，前端按 number|string 无损接收；小数位 0 是有效值。

输入节点的来源标为当前节点名。新建/改名字段标为创建它的节点，透传字段保留来源；条件分支向下游提供的字段标为条件节点。多输入同名字段来源不同会并列展示，不以任意第一条来源冒充唯一来源。表输出的 outputFields 表示启用映射实际写入的目标列结构，来源标为当前输出节点，并使用目标表的原生类型与长度；它不表示该节点向下游发送数据行。

技术复用：EtlSchemaService 提供已授权、冻结连接的只读元数据读取和普通转换推导，IntegrationSchemaService 负责领域授权、选择字段和写入映射。已支持的 Spark/Flink 图额外使用 EtlNativeSqlCompiler.analyze，与 compile 共用同一个字段形状生成过程。DataX 使用原编译器检查，无 HTTP 数组、任意命令或本地任务回退。

已支持完整源字段选择、纯字段选择/别名/删除、常量、已支持的字符串变换及连接后的字段形状。FIELDS 的类型变化、长度/精度转换，以及字段拆分的额外类型/长度/精度/格式转换会在任务检查中明确拒绝；DataX 自定义列必须显式指定类型，非空 format 也会拒绝，避免静默忽略。

未支持的引擎组件、外部文件/消息声明、Java/JavaScript 动态结果提供 partial 提示。脚本有 outputFields 时展示声明；没有声明时仅保留可能的输入字段名，类型未知。`complete=false` 和 warnings 不能当作可运行证明。元数据 SQL 只允许既有受限单表 SELECT；SQL 结果不会继承原生列长度、nullable 或十进制精度证明，写语句在驱动调用前拒绝。

实际只读 HTTP 验收见 [field-schema.json](verification/field-schema.json)：在独立 PostgreSQL 读取真实 varchar(100)、int8 非空主键和目标表结构，SQL 投影只返回所选字段、DELETE 拒绝；调用前后集成任务和记录的整表哈希一致，源数据保持三行。没有执行任务或应用 SQL 迁移。

数据库输出配置中的字段映射采用两列独立勾选和排序，按行配对；执行参数仍为 `fieldMappings:[{source,target,enabled}]`，只有两侧都勾选的有效字段才启用。`fieldMappingLayout:{version:1,source:[{name,checked}],target:[{name,checked}]}` 仅记录编辑器布局，恢复时必须与实际三元组一致，不能覆盖执行配置。缺失字段保留以便提示修复，启用映射会校验字段存在及目标列不重复；不在前端猜测数据库类型转换是否可执行。独立测试任务 1008 仅创建并暂存配置，未发布、未执行，供 `http://127.0.0.1:6001/dataforge/integration/designer/1008` 联调使用。
