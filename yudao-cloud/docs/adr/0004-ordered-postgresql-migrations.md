# ADR-0004：PostgreSQL 有序增量迁移

- 状态：Accepted
- 日期：2026-09-12

## Context

历史 SQL 文件命名和用途混杂，人工判断执行顺序容易遗漏依赖。全量基线适合新库，但不能安全替代已有数据库升级；运行时 DB Switch 搬数也不是发布迁移系统。

## Decision

1. 今后平台数据库变化使用 `sql/postgresql/migrations/NNNN_<module>_<short-description>.sql`，四位编号在目录内全局唯一。
2. 历史未编号脚本保持原路径；新迁移如依赖历史脚本，必须在文件头和交付说明明确前置。
3. 每个迁移包含 Migration、Purpose、Depends on、Target、Repeatable、Rollback 头信息和完整可执行增量。
4. 已合并、发布、交付或可能执行的文件不可修改；修正使用更高编号。
5. 新库全量基线同步最终结构，并在文件开头以唯一的 `-- Included migrations through: NNNN` 记录当前最高迁移；已有环境记录已执行编号和 SHA-256，拒绝同编号不同内容。
6. 创建和交付前运行仓库质量脚本；平台 CI 以脚本退出码作为合并门禁。

## Consequences

- 文件排序就是执行顺序，分支冲突能在合并前识别。
- 新库和存量库使用不同但可对账的升级路径。
- 迁移编号和内容成为发布身份，不能随意重写历史。

## Exceptions

对用户选择的外部/目标数据源动态生成的 DDL 不进入平台迁移；若功能同时改变平台任务表、字典、菜单或权限，平台部分仍必须提交编号迁移。

## Verification

- 运行 `python tools/quality/check_migrations.py`。
- PostgreSQL 专属语义和 repeatable 声明使用真实 PostgreSQL/Testcontainers 验证。
- 最终交付单列“SQL 执行顺序”，包含路径、对象、前置和风险。
