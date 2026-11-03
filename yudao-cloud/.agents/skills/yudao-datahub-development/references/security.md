# Security and Trust Boundaries

Read this reference when work changes authentication, authorization, internal RPC, public/open APIs, tenant or data scope, dynamic SQL, external database connections, files/object storage, downloads/exports, secrets, or sensitive logs. Confirm exact header names, filters, routes, security chains, and storage/database APIs from current source and configuration; examples in this reference do not prove a mechanism is already wired.

## Classify the Entry Boundary

Classify each entry point before copying a neighboring implementation:

- browser/admin traffic through the gateway;
- authenticated internal RPC between services;
- public/open API traffic from an external caller;
- job, callback, broker, or reconciliation processing without a browser session;
- outbound access to an external database, broker, HTTP service, or object store.

For every boundary define the authenticated principal, trusted context source, department and row/object scope, replay/idempotency policy, rate/resource limits, audit needs, and failure behavior. A frontend-hidden action, route metadata, guessed tenant, network location, or shared database is not authorization.

## Internal RPC and Identity Headers

- Treat login-user, tenant, data-permission bypass/rule, environment-routing, and similar context headers as privileged internal control data. Obtain their exact names and propagation behavior from the active starters and gateway configuration; do not duplicate literal names in business code.
- The public edge must remove client-supplied internal identity/control headers before establishing trusted context. A provider may trust propagated headers only after the caller is authenticated as an allowed service on a protected RPC route and the deployment has a verifiable network/routing boundary.
- `/rpc-api/**`, `@PermitAll`, or an internal-looking path does not authenticate a caller. The browser must never call RPC endpoints, and the public gateway must not route them. Sensitive providers need service authentication such as a validated signature or mTLS in addition to narrow exposure.
- 传递的用户上下文仍须经过提供方授权；验证业务对象与调用方身份，不允许消费方指定权限绕过标记或任意组织。DataHub 不接受租户头作为授权或业务路由依据。
- Test forged internal headers through the public edge, direct service access, missing/invalid service identity, gateway stripping, legitimate Feign propagation, identity mismatch, and data-scope enforcement.

## Public and Open APIs

- Give external clients a dedicated contract and credential identity; do not expose an internal Feign endpoint or reuse browser login headers as an open API.
- For signed requests, bind the signature to the credential/application identity, method, canonical path and query, body hash, timestamp, nonce, and protocol/signature version. Specify canonical encoding, allowed clock skew, key rotation/revocation, and constant-time signature comparison.
- Store nonces with an atomic insert-if-absent operation in a shared, durable-enough store, scoped by application and bounded by TTL. A per-process map or check-then-put sequence does not prevent replay across nodes.
- Replay prevention and domain idempotency are separate. A unique nonce rejects the same signed request; a stable business idempotency key must return or reconcile the same durable outcome across caller retries, including a timeout whose remote result is unknown.
- Apply credential-scoped authorization, rate/concurrency limits, payload limits, and audit without logging secrets, signatures, raw tokens, or sensitive request bodies.

## Dynamic SQL and External Databases

- Parameterize data values. Identifiers cannot be made safe with value placeholders: resolve them from authorized metadata or a strict allowlist, then render them through the verified DB Switch dialect/identifier contract. Never concatenate raw tenant, schema, table, column, order, expression, or connection input into SQL.
- User-authored SQL needs an explicit capability boundary. Define allowed statement classes and target schemas/objects, reject or safely parse multiple statements and comments according to the feature, enforce object authorization outside the SQL text, and apply statement timeout, row/byte limits, cancellation, and bounded concurrency. A parser accepting syntax does not authorize execution.
- Use a read-only database principal/transaction for read-only exploration when the target product proves those semantics. Do not promise read-only behavior solely from a UI toggle or keyword prefix.
- Obtain platform-managed datasource records and credentials through their owning module API. Keep secrets out of URLs returned to browsers, logs, exceptions, SQL history, job parameters, and durable task payloads; minimize credential lifetime and close connections/resources on every path.
- Validate outbound endpoint, driver, TLS/certificate, and connection-property policy. Database/JDBC connectivity can become an SSRF or internal-network path; do not accept arbitrary driver classes or unsafe URL properties merely because a connection test succeeds.
- Redact vendor errors before returning them externally while retaining a correlation ID and controlled operational detail. Read `references/dbswitch.md` for product-by-operation correctness; dialect support does not establish security.

## Files, Presigned URLs, Downloads, and Exports

- Business modules use the infra `FileApi` or another currently proven public contract, not `infra-server` internals or a directly embedded object-storage SDK.
- Scope a presigned grant to the intended user, object key or server-chosen prefix, HTTP method, content type, maximum size, checksum when required, and short expiry. Never trust a client-returned object key, URL, MIME type, or size without server-side verification.
- Treat object transfer and platform metadata/business registration as two stages. Finalize idempotently only after verifying the uploaded object, and define retry, cancellation, expired-grant behavior, compensation, and orphan-object cleanup. A successful storage PUT alone is not business success.
- Authorize downloads at issuance/finalization time, use safe server-controlled filenames and content-disposition, and prevent path traversal, cross-user key guessing, response-header injection, and indefinite URL reuse.
- Exports enforce the same department/object scope, column-level sensitivity, masking, and authorization as the source APIs. Bound synchronous exports; use an observable job for large output, with owner-scoped status and expiring download access.
- Neutralize spreadsheet formula injection for untrusted cells according to the active Excel utility, and audit material exports without placing exported data or credentials in logs.

## Verification and Evidence

Use focused negative tests in addition to the happy path: unauthorized object, forged context headers, replayed/open-API request, duplicate idempotency key, timeout with unknown result, unsafe identifier/statement, oversized upload/export, expired or cross-user signed URL, duplicate finalization, and sensitive-log inspection. Report which boundary was actually tested; a unit test of a helper alone does not prove gateway, network, database, or object-store enforcement.
