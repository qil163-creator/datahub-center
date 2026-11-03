# Governance 模块约束

本文件适用于 `yudao-module-governance/**`，与仓库根 `AGENTS.md` 同时生效。

- Governance 拥有标准、质量策略与报告、血缘持久化/查询、主数据、目录和治理策略；技术性的规则解析与审计 SQL 生成优先复用相应 framework 组件。
- 列表、详情、导出、修改和发布必须明确租户、部门或对象级数据范围。对象 Guard 和业务权限 API 不能用前端按钮隐藏或客户端 tenant/org 参数代替。
- 标准、质量和命名规则属于领域策略；通用数据库标识符、类型、DDL、元数据和执行机制优先进入 DB Switch 或职责相符的质量组件，业务侧只做领域配置与结果解释。
- 文档、标准和模型关联的发布/审批采用显式状态机、不可变快照和 CAS/锁；`FOR UPDATE` 必须位于真实事务并包含租户/对象作用域。
- BPM 启动、回调和取消不是本地事务的一部分。跨进程事件使用稳定事件信封，至少包含 `eventId`、schema/version、occurredAt、tenantId、aggregate/process correlation 和 revision；重复、延迟、乱序和未知结果都必须可对账。
- 审批、发布、授权和强制覆盖需要领域审计；`@ApiAccessLog` 不能替代 actor、对象、前后状态/摘要、原因、结果和 correlation 信息。
- 平台表、字典、菜单、权限或修复数据变化必须提交新的全局编号 PostgreSQL 迁移，并同步核对后端权限、前端权限和菜单组件路径。
