# 统一模型 PostgreSQL 迁移验证

此目录是配套测试资源，不是生产迁移。只针对隔离 PostgreSQL 实例执行。生产增量仍以 `sql/postgresql/migrations/0123_modeling_unified-definition.sql`、`0124_modeling_unified-target-cleanup.sql`、`0125_modeling_unified-menu.sql`、`0126_modeling_remove-workspace.sql` 为准。

前置条件：安装 Python 3、PostgreSQL 的 `psql` 和 `createdb`；创建隔离实例，向专用源测试库导入本次改造前 Git 版本的完整 `datahub-center.sql`。源库保留原 3 个物理模型示例，用于验证原始错误引用会被拒绝。不要用本次更新后的新库基线充当改造前数据，也不要指向开发、共享或生产实例。

执行示例：

```powershell
python yudao-module-modeling/yudao-module-modeling-server/src/test/resources/modeling-unified/verify_migrations.py --port 15437 --source-db modeling_before
```

脚本只连接 `127.0.0.1`，端口必须显式传入；测试用户默认 `postgres`，凭据通过 PostgreSQL 常规连接环境提供，不写入文件。脚本创建带随机后缀的 3 个克隆库，不修改源测试库，不自动删除任何数据库。

验证先完成历史准备与切换的 12 组，再验证 0125 和 0126：原样基线脏引用拒绝、准备重放、切换声明与审查门禁、默认/受限用户/部门权限等价、新设计草稿状态、切换重放、历史行和快照保真、租户与精确版本来源、绑定与证据不可变，以及检测、登记、删除版本、同名冲突、跨租户链接等负向场景。最后输出逐组结果；失败即终止。

`setup-valid-fixture.sql` 明确把示例物理模型 1 指向有效逻辑模型 3，以构造“已有链接但设计差异”的测试场景，并给独立物理模型 2 设置受限权限。这是合成测试输入，不是旧业务数据修复建议。批量审查调用也仅用于合成夹具，不得用于真实迁移的自动批准。

本脚本验证完整 0123—0126 升级链。所有旧 ACL、审查与冻结断言在 0126 之前执行；126 后只重放 126，不再查询已退役表。可传 `--baseline-db` 对已单独导入完整最新基线的新库执行最终结构只读断言。应用 API/界面、目标引擎 DDL 仍需分别验证。

## 工作台退役专项

`verify_workspace_removal.py` 以明确的 0125 空测试库为只读模板，创建四个随机克隆：验证七表完整原行归档、模型与版本 ID 保持、角色/套餐清理、重放证据不变、归档不可伪造、活动目标唯一约束，以及活动审批/引用/重复目标整笔阻断。源库必须没有物理模型，不使用生产快照。

```powershell
python yudao-module-modeling/yudao-module-modeling-server/src/test/resources/modeling-unified/verify_workspace_removal.py --port 15437 --source-db modeling_fresh_0125
```

2026-10-10 在专用 PostgreSQL 18 的 15437 端口实跑 8 组通过；最新 through0126 完整基线在独立空库导入成功。新库最终结构可执行 `post-workspace-removal-assertions.sql`。测试不修改任何用户目标数据源。
