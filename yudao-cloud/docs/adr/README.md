# DataHub 架构决策记录

本目录保存对多个模块、组件或交付流程具有长期约束力的架构决策。实现可以演进，但不能静默绕过已经接受的决策。

## 已接受决策

1. [ADR-0001：组件优先与能力归属](0001-component-first-capability-ownership.md)
2. [ADR-0002：DB Switch 能力归属与支持矩阵](0002-db-switch-ownership-and-capability-matrix.md)
3. [ADR-0003：ID、时间与精确小数线协议](0003-api-wire-contracts.md)
4. [ADR-0004：PostgreSQL 有序增量迁移](0004-ordered-postgresql-migrations.md)
5. [ADR-0005：跨服务一致性、RPC 信任与事件合同](0005-cross-service-consistency-and-trust.md)
6. [ADR-0006：数据 API 开放网关安全部署约束](0006-supply-data-api-security-deployment.md)

## 维护规则

- 新决策使用下一个四位编号，并写明状态、日期、背景、决策、后果、例外和验证方式。
- 已接受 ADR 不通过悄悄改写历史来改变结论。需要替代时新增 ADR，并在新旧文档中标记 `Superseded by` / `Supersedes`。
- 只影响单个实现细节且可由代码和测试完整表达的内容不必创建 ADR；跨模块所有权、公开合同、安全边界、迁移和不可逆选择应记录。
- PR 若偏离已接受 ADR，必须链接新的替代决策或明确说明临时例外、到期条件和跟踪项。
