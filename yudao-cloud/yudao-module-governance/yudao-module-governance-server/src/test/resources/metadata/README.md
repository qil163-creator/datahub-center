# 元数据版本与定版验证

采集队列、文件范围、服务权限与双向 TLS 的实际部署配置见 [采集运行契约](../../../../../docs/metadata-collection-runtime.md)。元数据采集 RPC 默认拒绝没有服务证书的调用；Governance 客户端 SSL bundle 与 DataForge 服务端证书指纹必须同时配置。

元数据发布开关与定版指针独立。`published_snapshot_id` / `published_version` 保留现有资产 RPC 引用名称，管理端另外返回 `finalizedSnapshotId` / `finalizedVersion`。确认定版选择本表任意结构版本，技术结构来自不可变结构快照；表业务信息、相同字段身份的业务信息、已确认标准版本和标签以确认时为准，保存在 `finalized_snapshot`。撤销发布不清除定版，撤销定版不取消发布；已有资产保存的历史结构快照引用不删除。

发布、取消发布、定版、撤销定版和正常表删除均校验 `stateRevision`。状态写入和业务维护均在真实事务中按库、表、字段顺序持锁；状态和业务修订分别使用 CAS。跨库批量认责先按编号锁定全部库再锁表。已发布表的业务属性和字段业务属性维护、批量认责及删除由后端拒绝；先取消发布再维护。普通删除还拒绝已挂接资产目录的表。最新元数据保持现有平台内可见范围及控制器权限，数据源富化仅输出名称，远程调用不在定版持锁事务中执行。业务备注的修改和清空使用 `REMARK` 属性记录前后值，不作为操作理由混用。

`MetaSnapshotViewsTest` 验证任意版本对比、字段新增/删除/修改、同名重建身份和大整数身份。`MetadataPublishedSnapshotTest` 验证资产 RPC 的冻结结构和表头，不用实时字段填充历史信息。`MetadataMaintenancePostgreSqlTest` 使用真实 PostgreSQL、生产 Mapper、Spring 事务代理，验证业务修订、标准关联、标签和定版状态竞争，并验证发布先完成时拒绝并发维护、维护先完成时定版保存已提交结果。

在独立的回环 PostgreSQL 测试库导入当前 `sql/postgresql/datahub-center.sql`，设置 `METADATA_WRITE_TEST_JDBC_URL`（只允许 `jdbc:postgresql://127.0.0.1:端口/测试库`）、`METADATA_WRITE_TEST_JDBC_USER` 和 `METADATA_WRITE_TEST_JDBC_PASSWORD`。使用仓库当前 Java 25：

```powershell
mvn -pl :yudao-module-governance-server -am '-Dtest=MetaSnapshotViewsTest,MetadataPublishedSnapshotTest,MetadataMaintenancePostgreSqlTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

迁移验证使用两个隔离测试库：一个导入最新全量基线，一个导入截至 0186 的基线并将 `0187_governance_metadata-management.sql` 执行两次。设置 `METADATA_MIGRATION_PG_PORT`、`METADATA_MIGRATION_FRESH_DB`、`METADATA_MIGRATION_UPGRADE_DB` 及上述测试用户环境变量后执行：

```powershell
python yudao-module-governance/yudao-module-governance-server/src/test/resources/metadata/verify_metadata_migration.py
```

验证器逐项比较表列、索引、约束与菜单，并实测 4096 字符中文路径的哈希索引唯一性。它只连接回环地址，并仅在新建的随机测试 schema 中写入验证数据。

SQL 执行顺序：已有库先满足 0186，再执行 `sql/postgresql/migrations/0187_governance_metadata-management.sql`；新库直接导入已包含 0187 的 `sql/postgresql/datahub-center.sql`。执行 0187 前需停止旧元数据采集进程；旧任务租约列会移除，旧运行实例首次迁移标记失败后由用户重新执行。迁移不删除源端文件或改动 infra 文件管理。
