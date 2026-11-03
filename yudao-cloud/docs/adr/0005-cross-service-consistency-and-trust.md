# ADR-0005：跨服务一致性、RPC 信任与事件合同

- 状态：Accepted
- 日期：2026-09-12

## Context

同步 Feign、BPM、MQ、文件和数据库之间不存在一个本地事务。项目同时存在需要即时结果的同步写和适合可靠异步处理的写入，因此不能一律要求 MQ，也不能把 `/rpc-api/** permitAll`、内部请求头或本地 ApplicationEvent 当成天然可信的跨服务边界。

## Decision

1. 跨服务读写只能通过提供方 `*-api` 或正式消息契约，禁止直接写其他服务业务表。
2. 根据即时结果、可用性和一致性需求选择同步 RPC 或异步消息。同步写定义超时、重试、幂等键、未知结果和补偿/对账；异步写定义可靠投递、重复/乱序、失败处置和恢复。
3. 不假定平台已经存在统一 outbox/inbox。复用前验证职责和恢复语义；没有合适实现时由领域提供 durable 状态、lease/崩溃恢复、最大重试、死信/人工处置、对账和清理。
4. 外部入口剥离内部身份、租户和数据权限控制头；服务只在经过服务身份认证的 RPC 通道信任这些头。`permitAll` 必须同时具备不对外路由、网络隔离或签名/mTLS 门禁。
5. 跨进程事件与本地 ApplicationEvent 分离。事件信封包含稳定 `eventId`、schema/version、occurredAt、aggregate/process correlation 和可排序 revision。
6. 开放 API 防重放与业务幂等分开设计；nonce 使用跨节点原子 TTL 存储，签名覆盖规范化请求和 body hash。

## Consequences

- 同步写仍可用于需要即时结果的合法场景，但失败状态必须可识别和恢复。
- 内部请求头不再被当作认证机制本身。
- 事件消费者可以处理重复、延迟、乱序和协议演进。

## Exceptions

同一进程内、无需跨重启持久化的通知可以使用 ApplicationEvent；它不得驱动必须跨节点可靠完成的业务状态。

## Verification

- 集成测试覆盖伪造内部头、直连服务、网关清洗和合法 Feign 传播。
- RPC 测试覆盖超时和未知结果；消息/BPM 测试覆盖重复、延迟、乱序、重试和对账。
- 开放 API 测试覆盖跨节点 nonce、过期时间、body/query 篡改和密钥轮换。
