# Component-First Backend Development

Read this reference for backend implementation or review whenever a task could introduce reusable technical behavior. Current root POMs, source, production callers, and tests override this capability snapshot.

## Mandatory Decision Path

1. Separate the requirement into domain policy/orchestration and technical mechanism.
2. Inspect the root `pom.xml` for active `yudao-framework` modules, then search component public APIs/SPIs, relevant business `*-api` artifacts, one or two production callers, and tests. A module name, enum value, or dormant class alone is not proof of a usable capability.
3. Reuse the existing public contract. Do not copy internal code, fork a dialect switch, or add a one-to-one wrapper whose only purpose is to hide the component dependency.
4. If a capability is missing, decide ownership before coding:
   - extend the component when the mechanism matches its responsibility, is independent of one domain, and can form a stable abstraction for another caller, product, or vendor;
   - retain a thin business adapter when only domain mapping, configuration assembly, datasource lookup, error translation, or orchestration is specific;
   - keep the implementation in the business module when it is a domain rule, permission decision, workflow/state transition, or genuinely one-off behavior.
5. For a component extension, define a backward-compatible API or SPI, keep business types out, implement the current consumer through it, and test both the component and affected consumers.
6. Record the search and ownership conclusion in the implementation/review notes whenever a new reusable technical class is introduced.

Do not create an abstraction for hypothetical reuse alone. Conversely, do not use “only one current caller” to justify placing an obviously vendor-neutral database or infrastructure primitive in a business module.

## Capability Ownership Map

| Concern | Inspect and reuse first | Boundary notes |
| --- | --- | --- |
| base objects, mapping, MyBatis wrappers and type handlers | `yudao-common`, `yudao-spring-boot-starter-mybatis` | Domain queries and invariants remain in the business module. |
| cache and Redis integration | `yudao-spring-boot-starter-redis` | Business cache keys and invalidation policy remain with the owning domain. |
| HTTP conventions, security, observability and request protection | web, security, monitor and protection starters | Reuse interceptors, handlers, annotations and configuration; business authorization still belongs to the module/API. |
| environment tag and gray-routing context | env starter | Reuse its environment header, Feign propagation and load-balancer selection rather than describing it as a generic request-context owner. |
| tenant and data-permission propagation | biz-tenant and biz-data-permission starters | DataHub 使用部门/对象授权，不依赖租户上下文；维护通用脚手架组件时保留已有租户模式。 |
| messaging, jobs, Excel and test bases | mq, job, excel and test starters | Prefer their templates, listeners, job configuration, conversion utilities and test bases over local infrastructure copies. |
| internal RPC/Feign | module `*-api`, `ApiConstants`, `@FeignClient`, plus the rpc dependency starter | The rpc starter is currently dependency aggregation; login, tenant, data-permission and environment propagation are implemented by their owning starters. Do not hand-code internal URLs or `RestTemplate` clients. |
| files and object storage | `yudao-module-infra-api` `FileApi` | Business modules must not bind directly to an object-storage SDK or depend on `infra-server`. |
| WebSocket sessions and delivery | websocket starter | Reuse its authentication, session registry, listener and sender abstractions. |
| IP and region lookup | biz-ip starter | Reuse its searchers and area utilities rather than adding another IP database/parser. |
| metadata, dialects, DDL, supported SQL generation/templates, readers/writers and runtime data movement | `yudao-spring-boot-starter-biz-dbswitch` | Read `references/dbswitch.md` and verify the exact implementation. Repository migrations remain numbered SQL files. |
| ETL executor mechanics and quality rule/audit mechanics | biz-etl and biz-quality starters | Pipeline definitions, quality policy and business lifecycle stay in their modules. |
| SeaTunnel and DolphinScheduler integration | their existing biz starters | Extend the existing client/configuration boundary instead of adding another client in a business module. |

For lineage, inspect `biz-lineage` for parsing/OpenLineage mechanics, while the current platform persistence/reporting contract is governance `LineageApi`. For any capability with historical and current ownership candidates, inspect the root README, active dependencies and production callers before choosing. Never resurrect an inactive, removed, or empty starter merely because its name matches the requirement.

The backend root POM is the authority for activation. At the time of this audit, `starter-config` and `biz-metadata` are not active, while `starter-rpc` is not an implementation of context propagation. The former standalone Flink runtime component has been removed, and DB Switch does not depend on it. Flink/Spark task submission uses the DolphinScheduler starter and DS Worker plugins; engine clients and dependencies belong to the execution environment. Do not restore removed modules from historical documentation. Recheck these facts from current source whenever they affect a task. A declared dependency also does not prove that a business module already uses a component.

## Database Mechanics

For metadata, product/dialect behavior, identifiers, native/common types, DDL, SQL templates, database operators, readers/writers, or runtime data movement, read `references/dbswitch.md`. That reference is separate so non-database component work does not need to load the detailed DB Switch capability inventory.

## Component Extension Gate

Before accepting a component change, check:

- responsibility: the behavior belongs to that component and has no dependency on a business module;
- contract: callers use a narrow public API/SPI and business DTOs/DOs do not leak into it;
- compatibility: existing callers and default configuration continue to work;
- runtime safety: connection/transaction ownership, thread safety, cleanup, tenant/security context, error semantics, logs and metrics are explicit;
- dependency cost: new libraries and drivers are optional or scoped where appropriate;
- evidence: focused unit tests cover the abstraction, dialect golden tests cover generated SQL, and real target databases cover behavior that mocks or H2 cannot prove;
- adoption: at least the current business caller is migrated to the component API, leaving only a thin domain adapter where needed.

## Reject These Duplication Patterns

- a per-business-module database product switch, identifier quoter, type mapper, DDL builder, JDBC metadata query, SQL splitter, pagination writer, or migration loop;
- copied component source with renamed classes;
- a local generic manager/executor that mirrors an existing component but bypasses its public extension point;
- moving business permission or lifecycle decisions into framework code to make an API appear generic;
- treating DB Switch auto-DDL or runtime movement as a substitute for ordered repository migration SQL.
