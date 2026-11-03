# Architecture and Module Ownership

Read this reference when placing a new capability, changing dependencies, adding a module API, or crossing service boundaries. Confirm all ownership facts against the current root POM, module READMEs, source, and callers; do not resurrect a removed module because an old scaffold or reference skill mentions it.

## Current Domain Routing

Use the existing domain boundaries as the default routing guide:

| Concern | Owning area |
| --- | --- |
| datasource records, drivers, synchronization, ETL, filling, SQL exploration | `dataforge` |
| subject/data domains, layers, classifications, logical and physical models | `modeling` |
| standards, quality, lineage persistence/reporting, master data, catalog and governance policy | `governance` |
| data APIs, applications, authorization and gateway-facing supply behavior | `supply` |
| assets and marketplace behavior | `asset` |
| shared process definitions and process-instance mechanics | `bpm` |
| system identity/RBAC/tenant administration and infrastructure tooling | `system` and `infra` respectively |

This is a decision aid, not permission to move unrelated code. If a requirement overlaps domains, select one owner for the invariant and expose a narrow module API or event instead of duplicating state machines in both modules.

## New-Module Gate

Do not create another `yudao-module-*` until all of these are true:

1. no active module owns the domain;
2. the capability has an independent lifecycle and a stable public boundary;
3. placing it in an existing module would create worse coupling than a new module;
4. root dependency management, service packaging, configuration, permissions, migrations, tests, and deployment impact are understood.

Otherwise extend the owning module. Never recreate historical `foundation`, `integrate`, `develop`, or similarly named modules without proof that the current root build and architecture require them.

## API/Server Boundary

- The provider owns cross-module contracts in its `*-api` artifact: DTOs, enums/constants, `ApiConstants`, Feign client, and public method semantics. Its `*-server` implements the API under the established `api/.../*ApiImpl` pattern.
- Consumers depend on the provider API artifact only. Update Feign registration where the current repository requires it; do not hand-build internal URLs, copy provider DTOs, or depend on provider `*-server`.
- Follow the provider's current `ApiConstants.NAME`/prefix and `CommonResult` contract. At consumers, use the established checked-result pattern when failure must become a local service error; do not silently ignore remote business failures.
- `/rpc-api/**` is an internal service surface. The browser must never call it. Internal routing is not a substitute for authentication and network isolation: expose only necessary data and add an explicit trust boundary for sensitive operations.
- A local `@Transactional` method cannot make Feign, MQ, BPM, files, or another service's database atomic. For cross-boundary writes, design idempotency, durable state, retry and reconciliation/outbox/inbox behavior according to failure risk.

## Cross-Service Interaction Semantics

- Use synchronous RPC when the caller cannot correctly continue without an immediate authoritative answer and the required latency/availability coupling is acceptable. Use asynchronous delivery when acceptance can be separated from completion, buffering and independent recovery matter, or a remote outage must not hold the caller's transaction open.
- A synchronous write must define a stable domain idempotency key, deadline, which failures are safe to retry, and what happens when the caller times out after the provider may have committed. Resolve an unknown outcome by querying/reconciling the provider with the same business identity before retrying side effects or applying compensation.
- An asynchronous write must define durable producer intent, message identity/schema, consumer idempotency, duplicate and out-of-order handling, retry limits, poison/dead-letter or manual action, and reconciliation. Search the active repository before naming an outbox/inbox implementation; do not assume a shared one exists.
- Compensation is a new authorized domain transition, not a distributed rollback. Define whether it is possible, idempotent, observable, and safe when the original result later becomes known.
- Never choose MQ solely to avoid an RPC failure or choose RPC solely because a Feign client already exists. Record the consistency and failure reasoning for material cross-service state changes.

Read `references/security.md` for internal-header trust, service authentication, gateway boundaries, and public API signing.

## Dependency Direction

- Framework starters must not depend on `yudao-module-*` or expose business VO/DO, permission codes, approval states, or module enums.
- Business modules may orchestrate framework capabilities and other module APIs, but must not reach into their internal implementations or tables.
- Avoid circular module APIs. When two domains need the same vendor-neutral mechanism, evaluate the appropriate framework component; when they share a business concept, establish one domain owner rather than creating a neutral dumping module.
