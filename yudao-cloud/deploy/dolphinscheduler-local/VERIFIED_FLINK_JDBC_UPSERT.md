# 受管 Flink 实时 JDBC 增量输出

产品入口为实时 `insert-db`，执行配置使用 `dataSyncType=2`、`upsertKeys:string[]` 与现有字段映射。历史追加配置不自动转换。当前完成真实验证的是 PostgreSQL，其他数据库继续明确拒绝。

控制面复用 `DatabaseService.getTableMeta` 的 DB Switch 主键及 nullable 事实，所有主键必须为确定的非空字段。管理员逐数据源声明已安装 `flinkVerifiedJdbcSinkInstalled`，并用 `flinkUpsertPrimaryKeys` 逐物理表登记原引擎 Catalog 的主键。默认值为空/关闭，节点不能自行授予安装或对象授权。若原表没有正确 JDBC PK，可新建独立 Catalog 写表并以 `flinkUpsertTableAliases` 仅为增量输出指定别名；原 `tableAliases`、读取与追加绑定保持各自原值。

`EtlNativeFlinkJdbcSinkSql` 用 `CREATE TEMPORARY TABLE ... LIKE` 继承原 Catalog 的完整字段、主键及连接选项，不在任务 JSON 或新 SQL 中复制凭据。新 `datahub-verified-jdbc` 工厂在引擎计划阶段再次验证原 JDBC URL 摘要、schema/table、Catalog 主键、NOT NULL 及空值失败策略，然后委托官方 JDBC sink。真实运行时检查空主键，转发官方 JDBC 生命周期与 checkpoint；未规范化的 UPDATE_BEFORE 拒绝。

主键映射必须完整启用。这里承诺的是按真实主键的幂等更新及删除，不能据此宣称跨系统事务或 exactly-once。

准备运行包（目标工作节点须已安装官方 Flink JDBC 及 PostgreSQL 驱动）：

```bash
python3 deploy/dolphinscheduler-local/prepare_verified_flink_jdbc_sink.py \
  --container components-stable-ds --output /tmp/datahub-verified-jdbc/binding.json
```

构建命令的 `--install` 仅发布内容摘要命名的不可变 JAR 到 `usrlib`，不等于正常任务已加载工厂。正常 LOCAL 部署应先校验绑定中的 SHA-256，再将该不可变 JAR 安装到目标工作节点 `/opt/flink/lib`，使新启动的会话能发现唯一的 `datahub-verified-jdbc` 工厂；不得同时保留另一份提供同名工厂的旧 JAR。仅使用本次核实的运行包和已有官方 JDBC/数据库驱动，不修改原 Catalog 或已有函数安装事实。

`NativeTaskParameters.withSqlInitialization` 会拒绝 `ADD JAR`。绑定文件中的 `ADD JAR` 只适用于独立直接调用 Flink 的验证脚本，不能拼进平台的 `flinkInitializationSql` 或节点 SQL。另一条可选加载途径是框架已有的受管 `dependencyResources` 强类型资源合同；DataForge 当前没有暴露相应配置时，不能用任意字符串配置冒充该合同。不要放宽 SQL 白名单来加载工厂。

管理员完成真实安装及 Catalog 核对后，才开启逐数据源的安装声明，并确保 Catalog 指向相同的数据库、schema/table 和 PK。仅修改安装布尔值不能替代真实运行包或 Catalog 定义，工厂会失败闭合。`DH_WINDOW_PICK` 等独立函数继续使用各自既有的 `FUNCTION ... USING JAR` 加载合同，不能与表工厂安装混用。

实际验证入口：

```bash
python3 deploy/dolphinscheduler-local/native_flink_upsert_smoke.py \
  --binding /tmp/datahub-verified-jdbc/binding.json \
  --output /tmp/datahub-verified-jdbc/verification.json
```

运行前先执行 `EtlNativeFlinkUpsertTest` 生成真实编译 SQL 模板。探针创建无宿主端口的独立 PostgreSQL 容器与网络，在官方 Flink 1.20.3 STREAMING 模式下实际写入；最后清理专属数据库与网络。2026-11-03 的五个场景均通过：重复键更新、大整数与 emoji 保真、UPDATE_AFTER/DELETE；Catalog 未声明 PK、目标身份摘要错误、空主键均拒绝且无写入。证据为 `verification/flink-verified-jdbc-upsert-20261103.json`。这是独立有限测试流验证，不宣称持续 CDC 或故障恢复已验收。
