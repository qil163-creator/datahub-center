# Backend Constraints

Read this reference for backend implementation or review in `yudao-cloud`.

## Module Boundary

- Read the root `pom.xml` to determine active modules and current versions.
- Keep business modules in `yudao-module-<module>/{api,server}`.
- Put cross-module interfaces, DTOs, constants, and shared enums in `*-api`; put implementations in `*-server` under `api/.../*ApiImpl`.
- Depend only on another module's API artifact. Do not import its Controller, Service, Mapper, DO, or server artifact.
- Cross-service reads and writes use the provider's `*-api` contract or a formal message/event contract; never mutate another service's business tables. Select synchronous RPC or asynchronous delivery from outcome, latency, availability, and recovery semantics rather than imposing one mechanism on every write. Read `references/architecture.md` for provider ownership, internal RPC trust, and distributed consistency.

## Component-First Boundary

- Read `references/components.md` before introducing a technical helper, executor, manager, factory, parser, converter, dialect, template engine, or integration client.
- Business modules own domain policy, authorization decisions, state transitions, and orchestration. Active framework components own reusable technical mechanics.
- Reuse a component through its public API or SPI. A thin business adapter may map domain objects, assemble configuration, resolve a business datasource reference, or translate errors, but it must delegate the underlying mechanism instead of copying it.
- If a missing capability is generic and belongs to an existing component, add the stable contract, implementation, compatibility behavior, and tests in that component first, then consume it from the business module. Keep one-off or unstable domain abstractions local.
- A framework component must not depend on `yudao-module-*` or expose business VO/DO types, permission codes, approval states, or module-specific enums in its public contract.

## Standard Vertical Slice

For ordinary admin CRUD, follow the active generator and a mature tracked example such as governance `standardwordroot`:

- `controller/admin/<business>/<XxxController>` and `vo/{XxxSaveReqVO,XxxPageReqVO,XxxRespVO}`;
- `service/<business>/{XxxService,XxxServiceImpl}`;
- `dal/dataobject/<business>/XxxDO`;
- `dal/mysql/<business>/XxxMapper`;
- `resources/mapper/<business>` only when custom SQL is clearer than wrappers.

Use these constraints:

- Controller: `@RestController`, resource-level `@RequestMapping`, `@Validated`, OpenAPI `@Tag`/`@Operation`, Jakarta request validation, fine-grained `@PreAuthorize`, and `CommonResult`. Do not expose a DO or include `/admin-api` in the mapping.
- VO: put required, length, pattern, and format validation here. Page requests extend `PageParam`.
- Service: put existence, uniqueness, state, reference, authorization, transaction, and concurrency rules here. Validate update/delete targets explicitly.
- Error handling: use the owning module's `*-api` `ErrorCodeConstants` and `ServiceExceptionUtil.exception`. Before allocating a value, inspect the current `ServiceErrorCodeRange` and `tools/quality/error_code_ranges.json`; do not invent an exclusive range for a module that still shares a legacy namespace. Run `python tools/quality/check_error_codes.py` to enforce repository-wide uniqueness, canonical literal form, package/module ownership, and configured ranges.
- Conversion: use framework `BeanUtils` for simple same-shape mapping. Use an explicit converter plus focused tests for JSON, nested, computed, enriched, or type-changing mapping.
- DO: normally extend `BaseDO`; do not redeclare audit or logical-delete fields. DataHub 业务 DO 不继承 `TenantBaseDO`，不声明 `tenantId`；脚手架实体按自身契约维护。
- Mapper: extend `BaseMapperX` and prefer `LambdaQueryWrapperX` conditional methods. Add XML only for SQL that wrappers cannot express clearly.
- Transactions: add `@Transactional(rollbackFor = Exception.class)` for multi-write invariants, state transitions, or locks. Verify the call crosses a Spring proxy and reaches a proxied method; self-invocation, non-proxied construction, incompatible visibility, and work moved to another thread can make the annotation ineffective. Do not add it to every simple CRUD method.

## Transactions, Locks, and Caches

- Acquire a database row/advisory lock or Lock4j lock only after defining its key, scope, wait/lease behavior, transaction ownership, retry semantics, and failure response. Confirm a database lock query executes inside the real transaction and that no unbounded Feign, BPM, broker, or file call is made while holding it.
- `@Idempotent`, a distributed lock, an optimistic version, a database unique constraint, and a durable operation record solve different failure modes. Select the invariant first; do not substitute one mechanism merely because its annotation already exists.
- 复用 Redis/cache starter；DataHub 缓存按业务对象及必要的用户/应用范围设计 key，不添加租户前缀或要求租户上下文。
- Coordinate invalidation/publication with successful commit, normally through an after-commit mechanism or a recoverable durable path. A cache or local event is not the source of truth for a cross-service write.

## External Contract and Operational APIs

- Treat Java types and wire JSON types separately. The current global serializer can emit a `Long` as a JSON number inside JavaScript's safe range and as a decimal string outside it; frontend contracts and generated output must preserve both without numeric coercion. The default `LocalDateTime` wire form is epoch milliseconds unless that field has an intentional `@JsonFormat`. Verify the actual VO annotations before documenting or typing an endpoint.
- For money, ratios, measurements, or other exact decimals, define the JSON representation explicitly. If JavaScript precision must be preserved, serialize the value as a decimal string at the backend boundary and validate/deserialize the matching request form; changing only a TypeScript type does not change JSON bytes. Cover scale, rounding, trailing zeros, very large values, and negative values in raw serialization/deserialization contract tests.
- Import/export endpoints use the existing Excel utilities and established `@ApiAccessLog` operation types. Apply the same permission and row-level scope as list/detail APIs; exports must not become an authorization bypass.
- Evaluate business-operation audit records for material approvals, releases, permission changes, irreversible actions, and other traceability requirements. Do not add `@LogRecord` mechanically to every CRUD write.
- Keep batch endpoints bounded and explicit about partial failure, validation, authorization, idempotency, and transaction scope. Do not load an unbounded table solely to export or mutate it.

## Authorization and Tenancy

- DataHub 业务表不含 `tenant_id`，运行时关闭脚手架租户开关；不得引入固定租户或通过 `@TenantIgnore` 掩盖业务租户依赖。
- Never trust a client-supplied tenant or organization identifier as authorization.
- For every new list, detail, export, update, and delete path, decide and document one of: platform-wide visibility, department data rule, object-level guard, or business permission API.
- Do not add `@DataPermission` mechanically. Confirm the target table and user/department columns are registered for the selected rule.
- Keep `@PermitAll`, and security whitelist changes narrow and justify the alternative trust boundary.

## Configuration and Secrets

- Use validated `@ConfigurationProperties(prefix = "yudao.<module>.<feature>")` for cohesive configuration instead of scattered string lookups.
- Never commit real credentials, tokens, database connection secrets, or sensitive values to configuration, SQL, fixtures, or logs.

## Source Anchors

Use semantic paths rather than fixed line numbers:

- root `pom.xml` and `README.md`;
- infra `CodegenEngine`, Java/SQL templates, and codegen application configuration;
- framework `BaseDO`, `BaseMapperX`, `LambdaQueryWrapperX`, tenant interceptor, data-permission annotation, and web properties;
- governance `standardwordroot` for a mature CRUD slice;
- modeling `PhysicalModelConvert` for complex conversion;
- governance `StandardDocServiceImpl` and PostgreSQL tests for locking and transactions.
