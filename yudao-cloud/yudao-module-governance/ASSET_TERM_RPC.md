# 资产引用治理业务术语

## 能力与边界

治理业务术语是名称、别名、业务定义和启停状态的唯一维护入口。资产只保存术语编号并通过 `StandardTermApi` 读取当前定义；治理更新定义后，资产下次读取即可反映修改。此接口不冻结定义版本，也不改变已有 `StandardReferenceApi` 的版本摘要协议。

术语在所属租户内可见。资产继续负责资产及字段的访问权限；本接口不接受 `tenantId` 请求参数，不开放浏览器直接调用。跨服务只读，不向治理发起术语写入。

| 方法 | 语义 |
| --- | --- |
| `getEnabledTermList(keyword)` | 可选关键词最多 100 字符；去除首尾空白后匹配名称或别名；只返回启用项，按名称和编号排序，最多 50 条。 |
| `getTermList(ids)` | 1 至 200 个正整数编号；返回当前租户仍存在的术语，包括停用项；删除、不存在、其他租户的编号不返回。返回顺序不作为契约。 |

响应字段为 `id/name/definition/alias/status`。`status` 为 `0`（启用）或 `1`（停用）。`Long` 编号沿用框架线协议：安全范围内为 JSON number，超出 JavaScript 安全整数范围为十进制字符串；消费端不得转换为有损数字。

## 部署前置条件

新术语 RPC 复用 security starter 的 `VerifiedServicePeerResolver` 和 rpc starter 的 `MutualTlsFeignClients`。两者已由模式脚本等现有接口使用，没有新增证书协议或全局 Feign 配置。可以复用部署中已有、能标识资产服务的证书、信任链和 Spring SSL bundle，无需为术语签发独立证书。

**未配置服务间 TLS 时，应用可以启动，但术语远程读取会失败。** 默认不信任任何调用者；不要把启动成功当作功能已完成部署。

部署方须同时完成以下配置：

1. 治理服务的 Servlet 容器提供 HTTPS，校验资产客户端证书链，并将实际握手证书写入 Servlet 标准属性。允许其他 HTTPS 接口不提交客户端证书时，可使用容器的可选客户端证书模式，术语过滤器仍强制要求证书。只由反向代理添加 `X-Forwarded-Client-Cert` 不满足条件。
2. 资产服务已有或配置一个 Spring `SslBundle`，包含客户端证书/私钥和治理服务器的受信任 CA。通过 `yudao.governance.standard-term.client.ssl-bundle` 指定 bundle 名。私钥和证书库密码由部署环境的密钥管理提供，不提交仓库。
3. 治理配置 `yudao.governance.standard-term.rpc.peers.asset`，值为允许的资产服务叶证书 DER 的 SHA-256 十六进制指纹集合。只接受 `asset` 服务标识；证书轮换期间可以并存多个指纹。
4. 服务发现提供的治理实例最终地址必须为 HTTPS，证书必须匹配目标主机名。使用显式 Feign URL 的部署同样必须指向 HTTPS。客户端不降级 HTTP、不跟随重定向，连接超时 2 秒、读取超时 5 秒，不自动重试。
5. 公网网关保持禁止路由 `/rpc-api/**`，清理外部请求的内部身份/控制头。治理术语过滤器在租户上下文解析前检查实际证书；两个 API 方法再次检查服务端验证标记，避免因漏注册过滤器而放开调用。

仅本次新增的配置示例（值由部署环境提供）：

```yaml
# 资产服务配置；bundle 名指向现有或已配置的 Spring SslBundle。
yudao:
  governance:
    standard-term:
      client:
        ssl-bundle: ${ASSET_GOVERNANCE_SSL_BUNDLE}
```

```yaml
# 治理服务配置；指纹不是账号、令牌或私钥。
yudao:
  governance:
    standard-term:
      rpc:
        peers:
          asset:
            - ${ASSET_CLIENT_CERT_SHA256}
```

若缺少 bundle/信任链/证书指纹，先补齐部署条件，再验证资产字段术语选择和展示；不得移除认证过滤器或回退为信任 `login-user`/`tenant-id` 请求头。

## 验证

集中 Maven 可选择 `StandardTermApiImplTest,StandardTermRpcSecurityTest,StandardTermLookupPostgreSqlTest`，跨 reactor 指定测试时增加 `-Dsurefire.failIfNoSpecifiedTests=false`。

- `StandardTermApiImplTest`：DTO 映射、当前定义、停用项、大编号 JSON 往返、请求大小边界。
- `StandardTermRpcSecurityTest`：伪造头和未认证直连拒绝、服务/证书匹配、过滤器顺序、缺配置失败关闭、Feign 契约及租户/编号传播。该测试使用 Mock Servlet 提供证书属性，不证明部署环境已完成真实 TLS 握手。
- `StandardTermLookupPostgreSqlTest`：在真实 PostgreSQL 上验证名称/别名条件分组、租户隔离、逻辑删除、启停状态、50 条上限和大编号查询。默认使用 Testcontainers；无 Docker 时会明确跳过，不能计为通过。

本机独立临时 PostgreSQL 可通过以下环境变量代替 Testcontainers：

- `STANDARD_TERM_TEST_JDBC_URL`：显式 `jdbc:postgresql://127.0.0.1:<port>/standard_term_<name>_test`。任务临时实例也允许数据库名 `codex_term_migration`。
- `STANDARD_TERM_TEST_JDBC_USER`：专用角色 `standard_term_test`；任务临时实例也允许 `codex_term`。
- `STANDARD_TERM_TEST_JDBC_PASSWORD`：可选；只由环境提供。

测试只新建、清理自己的 `standard_term_test_<UUID>` schema，既不重建调用方数据库，也不清理其他 schema。显式外部配置不合法或连接失败时，测试失败而不会静默退回 Docker。真实服务上线前仍须做一次资产到治理的实际证书握手和租户联调。
