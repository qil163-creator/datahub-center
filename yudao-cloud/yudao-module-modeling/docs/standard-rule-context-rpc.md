# 数据元规则候选 RPC

本说明仅适用于新增的 `GET /rpc-api/modeling/model-field/rule-context-field-list`。旧模型字段同步接口不在本次改造范围。

治理模块通过 `ModelRuleContextApi` 的独立 Feign context 调用此接口；只允许这一条 GET 路径，使用 framework `MutualTlsFeignClients` 的 HTTPS、证书校验、超时与无重试传输。建模模块通过 framework `VerifiedServicePeerResolver` 读取 Servlet 容器的 TLS 客户端证书属性，匹配治理服务叶证书 SHA-256 指纹；不接受 HTTP 头携带的证书身份。

## 部署配置

- 治理服务设置 `yudao.modeling.rule-context.client.ssl-bundle`，指向已配置的 Spring SSL bundle。该 bundle 提供治理服务客户端证书、私钥及对建模服务端证书的信任链，密钥放在部署密钥管理中。
- 可设置 `yudao.modeling.rule-context.client.url` 为建模服务的 HTTPS 地址；不设置时走服务发现，发现得到的最终实例地址也必须是 HTTPS。缺失 bundle、明文实例或不受信任证书均失败关闭。
- 建模服务设置 `yudao.modeling.rule-context.rpc.peers.governance` 为允许的治理客户端叶证书 DER 的 SHA-256 十六进制指纹集合。允许同一服务配置多个指纹以支持轮换，默认集合为空。其他服务名称和有歧义的指纹配置启动时拒绝。
- 建模 Servlet 容器必须直接校验 mTLS 客户端证书并提供 `jakarta.servlet.request.X509Certificate` 属性。TLS 仅在代理终止、再使用普通 HTTP 转发时无法满足本契约。推荐为内部监听配置客户端证书必需；不得用 `X-Forwarded-Client-Cert` 代替握手认证。
- 公共网关不得路由 `/rpc-api/**`；新接口还需保留内部网络访问限制。前端只调用治理管理端接口。

## 用户权限与数据范围

治理侧新增 `rule-*` 管理端接口首先清除外部传入的身份、租户、数据权限开关和环境路由头，并要求真实访问令牌；不允许开发模拟令牌进入这条传播链。合法用户由现有 `LoginUserRequestInterceptor` 传播。

建模侧仅在治理服务身份验证成功后读取管理员用户编号和类型，重建最小用户身份，清除其他授权控制上下文，再执行 `modeling:model:query` 权限检查。单租户平台模型目录采用平台可见加 RBAC；结果只包含当前仍引用指定数据元的模型及这些模型的完整字段。治理保存时间链、互斥条件时要求字段属于同一候选模型；主数据引用两端分别校验候选模型和字段归属。

此接口提供配置候选，不执行模型数据。新增关联规则随标准版本冻结为 `rule_bindings_snapshot`，现有发布质量消费者继续读取原有发布约束快照和 hash，本轮未合并或自动执行新增关联规则。

## 验证边界

聚焦测试覆盖缺失证书、伪造转发证书头、错误服务证书、缺失或重复用户头、非管理员用户、授权控制头清理、真实权限拒绝与允许、合法 Feign 用户编号传播、独立 Feign 子上下文、缺失 TLS 配置失败关闭及新接口的模型范围。测试使用容器证书属性的确定性模拟；实际部署 TLS 握手、密钥配置、服务发现 HTTPS 元数据及公共网关路由仍需按部署环境验证。
