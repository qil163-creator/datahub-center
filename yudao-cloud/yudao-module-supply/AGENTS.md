# Supply 模块约束

本文件适用于 `yudao-module-supply/**`，与仓库根 `AGENTS.md` 同时生效。

- Supply 拥有数据 API、应用、授权和网关侧供应行为；不得直接修改 DataForge、Governance、Asset 等模块业务表，跨模块访问使用提供方 `*-api` 或正式消息契约。
- 对外 API 的防重放与领域写幂等分别设计。nonce 必须使用跨节点原子、带 TTL 的存储并按应用隔离，不能使用 JVM 本地集合承担集群安全边界。
- 签名串覆盖 method、规范化 path/query、body hash、timestamp、nonce 和协议版本，明确时钟窗口、常量时间比较、密钥轮换、旧密钥过渡与审计脱敏。
- 外部请求不能注入内部 `login-user`、tenant、data-permission 等控制头；内部 RPC 只有在服务身份已认证、网关清洗和网络边界可证明时才信任这些头。
- API 版本变化优先使用 additive DTO 和兼容字段；需要破坏性变更时并存新方法/路径并给出消费者迁移窗口。修改未被运行时引用的 `ApiConstants.VERSION` 不等于完成版本治理。
- 查询执行必须限定授权对象、行数、时间、资源与可执行语义；用户输入不得通过字符串拼接突破标识符、参数或 SQL 安全边界。
