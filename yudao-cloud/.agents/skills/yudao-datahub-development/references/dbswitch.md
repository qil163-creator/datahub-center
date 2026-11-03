# DB Switch Ownership and Extension

Read this reference whenever backend work discovers metadata, dialect, identifier, type, DDL, SQL-template, database-operator, bulk-write, or runtime data-movement behavior. Inspect current source, registered implementations, dependencies, production callers, and tests before deciding that a named capability is complete.

## Reuse First

Use the active `yudao-spring-boot-starter-biz-dbswitch` as the first stop for:

- product detection and implementations through `DatabaseFactory`, `IDatabaseInterface`, and registered extensions;
- JDBC metadata through `IMetaDataByJdbcService` and its implementations;
- create-table SQL through the active `GenerateSqlUtils` path and product-specific builders such as `DorisDdlBuilder`;
- database operators, readers/writers, synchronization factories, and `MigrationService` for runtime full or incremental movement;
- any existing identifier, type-conversion, SQL rendering, parsing, template, or capability-discovery contract proven by callers and tests.

A thin business adapter may resolve a platform datasource, map domain configuration, translate errors, and orchestrate state. It must delegate the generic database operation to DB Switch instead of duplicating its switch statements or JDBC loops.

## Verify Before Depending

- A `ProductTypeEnum` member or interface declaration does not prove that the driver, factory registration, every operation, or its tests exist.
- The main DDL path supports creation for multiple products, but is not automatically a lossless schema-diff or complete schema-evolution engine.
- Older `ISqlGeneratorService` and Calcite conversion paths are limited. Do not make them a new default without verifying supported operations and consumers.
- The Orange dynamic SQL engine serves its established parameterized query semantics; do not treat it as a generic DDL-template registry without an explicit compatible extension.
- The portable column/type model may not retain native JSON/JSONB, UUID, arrays, enums, geometry, collation, generated columns, precision, or timezone semantics. Test every native feature that matters.
- Task-specific datasource metadata and migration objects have connection and configuration ownership. Do not convert them mechanically to shared singleton beans; verify isolation, concurrency, cleanup, and credential lifetime.
- `MigrationService` performs runtime data movement. It does not imply cross-table atomicity, exactly-once delivery, resumability, deployment checksums, ordered release history, baselines, or rollback orchestration.

Use the owning DataForge module API to obtain platform-managed datasource information and adapt it to DB Switch. DB Switch must not take ownership of business datasource records or credential workflows.

## Verified Product-by-Operation Matrix

Do not describe a database product as simply “supported.” Build the smallest matrix required by the task and classify each product/operation cell independently:

| Product | metadata | DDL | read | write | full sync | incremental sync |
| --- | --- | --- | --- | --- | --- | --- |
| requested product | proven / partial / unsupported / unknown | proven / partial / unsupported / unknown | proven / partial / unsupported / unknown | proven / partial / unsupported / unknown | proven / partial / unsupported / unknown | proven / partial / unsupported / unknown |

A cell is `proven` only when the current factory/registration and driver exist, the concrete operation path is implemented, and a focused test or production caller establishes the required semantics for that product. Record the source/test evidence and important limits such as identifier case, schema/catalog behavior, native types, pagination, transaction support, batch behavior, watermark types, or DDL coverage. An enum entry, dependency, generic interface, another product's test, or success of a different operation leaves the cell `unknown` or `partial`.

Scope the matrix to operations the feature will actually invoke; do not manufacture a repository-wide support promise. Fail closed or expose an explicit capability result for unsupported/unknown cells rather than falling through to another dialect. Include the affected cells and evidence in the handoff when adding a product, changing a dialect, or widening an operation.

## Extension Decision

When current behavior is insufficient, first classify the gap:

- Extend DB Switch when it is a vendor-neutral or product-specific database mechanism with a stable contract, such as dialect capabilities, identifier policy, logical/native type mapping, immutable DDL models and plans, schema comparison, same-connection script execution, safe rendering, or capability discovery.
- Keep a thin adapter in the business module for datasource lookup, domain model mapping, configuration assembly, error semantics, task state, retry, and orchestration.
- Keep domain policy outside DB Switch: modeling approval/version/release gates, object and department authorization, scheduling, notifications, menus, dictionaries, datasource ownership, and domain seeds.

Prefer consolidating overlapping internal paths over adding a third dialect or DDL abstraction. When a duplicate business-local identifier quoter, type mapper, schema comparer, SQL splitter, JDBC metadata query, or migration loop is in task scope, document which DB Switch APIs were checked and why the solution reuses, extends, or deliberately remains outside the component.

## Extension Acceptance Gate

An accepted DB Switch extension must establish:

- a business-neutral public API or SPI and backward-compatible default behavior;
- explicit product support and failure behavior for unsupported capabilities;
- connection/transaction ownership, thread safety, cleanup, credential handling, errors, logs, and metrics;
- optional/scoped drivers and dependencies where applicable;
- focused contract and dialect golden tests, plus a real target database/Testcontainers for metadata, native types, DDL execution, transactions, locks, bulk writes, or movement semantics that mocks/H2 cannot prove;
- adoption by the current consumer with only a thin domain adapter remaining.

## Deployment Migration Boundary

Runtime DDL or movement against a user-selected external datasource is not a platform deployment migration. Conversely, every platform-owned schema/data change still requires the ordered PostgreSQL process in `references/database.md`. A feature can require both paths; never use DB Switch auto-DDL or `MigrationService` as a substitute for `sql/postgresql/migrations/NNNN_*.sql`.

For user-authored queries, identifier rendering, script execution, credentials, or external connection handling, also read `references/security.md`; dialect correctness does not establish authorization or injection safety.
