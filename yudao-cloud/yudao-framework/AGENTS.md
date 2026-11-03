# Yudao Framework 组件约束

本文件适用于 `yudao-framework/**`，与仓库根 `AGENTS.md` 同时生效。

## 能力归属

- Framework 只承载与具体业务领域解耦、具有稳定复用边界的技术机制；不得依赖 `yudao-module-*`，公共 API、配置和模型不得出现业务 VO/DO、权限码、审批状态或模块枚举。
- 新增能力前检索当前启用的 starter、公共 API/SPI、生产调用方和测试。存在名称、枚举或接口不代表能力完整；必须验证实际实现、自动配置、依赖和调用链。
- 只有能够形成稳定契约且至少服务当前真实调用方的能力才下沉。业务侧保留领域映射、数据源记录解析、配置组装、错误语义和流程编排；不得复制组件内部实现或创建一对一包装。
- 组件变更必须说明向后兼容、默认配置、条件装配、线程安全、连接/事务所有权、资源清理、租户/安全上下文、异常与观测语义，并验证组件自身和受影响的直接消费者。

## 缓存、幂等与锁

- 缓存复用 Redis starter；开启租户时复用租户缓存命名空间，不在业务层再次拼接 tenant key。缓存写入/失效与事务提交保持一致，并验证回滚和跨租户隔离。
- Protection `@Idempotent` 只处理有限时间窗内的 HTTP 重复提交，不能代替数据库唯一约束、业务幂等记录、任务/MQ/BPM 回调幂等。
- 临界区互斥优先评估 Lock4j；持久不变量仍使用唯一约束、乐观锁/CAS 或数据库行锁。任何锁都要定义作用域、租户维度、超时、续期和失败行为。

## DB Switch

- 元数据、产品/方言、标识符、通用类型、DDL、SQL 模板、数据库操作器、reader/writer 和运行时搬数优先归入 `yudao-spring-boot-starter-biz-dbswitch`。
- 用“数据库产品 × metadata/DDL/read/write/sync”矩阵声明能力；每个格子都由注册、驱动、调用方和测试证明。不允许从 `ProductTypeEnum` 或单个实现推断整种产品完整支持。
- 新方言或操作通过现有工厂/API/SPI 扩展，明确 unsupported 行为、连接和事务边界；方言输出做 focused golden test，原生类型、DDL 执行、锁、批量写和搬数使用真实目标数据库/Testcontainers。
- DB Switch 的运行时 DDL/搬数不承担平台数据库的版本升级，平台结构和数据变化仍走 `sql/postgresql/migrations`。

## 版本与验证

- Java、Spring Boot、Spring Framework、Jakarta、Jackson、MyBatis 和插件版本从当前 POM/effective POM 获取。修改根 POM、BOM 或 starter 时检查版本对齐、dependency convergence、annotation processor 以及 Jackson 2/3 边界。
- 编译成功不能证明自动配置和运行时兼容；至少运行受影响组件测试、最小应用上下文和直接消费者验证。Testcontainers 被跳过时必须明确报告。
