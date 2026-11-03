# ADR-0002：DB Switch 能力归属与支持矩阵

- 状态：Accepted
- 日期：2026-09-12

## Context

DataForge、Modeling、Governance 都会接触数据库元数据、方言、标识符、类型、DDL 和数据搬运。仅看到产品枚举或接口，不能证明某数据库的所有操作已经可用；在业务模块重复实现方言分支又会造成长期分叉。

## Decision

1. 元数据发现、产品/方言识别、标识符、通用/原生类型映射、DDL、SQL 模板、数据库操作器、reader/writer 和运行时搬数优先由 DB Switch 提供。
2. 能力按“数据库产品 × metadata/DDL/read/write/sync”矩阵声明和验证，不使用“支持某数据库”这种无操作维度的笼统结论。
3. 新产品或通用数据库机制通过现有工厂、接口或 SPI 扩展；unsupported 行为必须显式失败。
4. 业务模块只保留平台数据源记录解析、领域配置映射、权限、审批/版本状态、调度、错误翻译和任务编排。
5. DB Switch 运行时 DDL/搬数不承担平台数据库版本升级。

## Consequences

- 同一数据库能力不会在多个业务模块重复实现。
- 每个产品只对已经有注册、驱动、调用方和测试证据的操作做出承诺。
- Native 类型、连接/事务、并发与清理成为组件合同的一部分。

## Exceptions

尚未形成稳定数据库抽象的领域规则可暂留业务模块，但不得复制已有 DB Switch 的产品 switch、JDBC 元数据循环、标识符或类型映射。

## Verification

- 为受影响的矩阵单元增加契约或 golden-output 测试。
- 元数据、原生类型、DDL 执行、锁、批量写和搬数使用真实目标数据库/Testcontainers。
- 验证 factory/SPI 注册、驱动存在性、资源关闭和当前业务消费者。
