# Runtime Integrations

Read this reference for scheduled/distributed jobs, asynchronous work, MQ/events, file storage, WebSocket delivery, imports/exports, and audit behavior. Search the active starters and current production callers before adding a client, executor, listener, scheduler, serializer, or retry mechanism.

## Jobs and Scheduling

- Use the active job starter and XXL-Job pattern for operator-visible, distributed, retryable, or manually triggered business jobs. Keep the handler thin: parse/validate parameters, establish context, delegate a service, and report a truthful success/failure result.
- DataHub 任务在单平台范围执行一次，不使用 `@TenantJob`、`@TenantIgnore`、租户参数或租户上下文恢复。分页扫描并按业务对象抢占 lease、锁或 CAS；明确执行人和对象授权。事务以业务项为边界，不覆盖远程或长时间执行。
- A retry or duplicate dispatch must not corrupt state. Use a business key, status guard, unique constraint, lock, checkpoint, or durable execution record appropriate to the operation; logging a duplicate is not idempotency.
- Use Spring `@Scheduled` only for deliberate internal maintenance/heartbeat work. In a cluster it may run on every node unless a lock or topology rule says otherwise; document the intended cluster semantics.
- Do not put unbounded scans, blocking remote loops, or large transactions in a scheduler thread. Page/partition work, bound concurrency, expose progress, and define timeout, retry, cancellation, and recovery.

## Async Context

- Prefer configured executors and existing context propagation. Raw threads and common pools do not automatically carry login, data-permission, tracing, locale, or transaction context.
- Capture only the explicit immutable context required by background work and re-establish it through supported APIs. Never copy credentials or retain request objects longer than their request.
- Define queue bounds, rejection behavior, cancellation, cleanup, exception reporting, and shutdown behavior. A fire-and-forget future must not be the only record of durable business work.

## MQ and Events

- Distinguish the platform's internal message/event infrastructure from DataForge connectors that send user-configured external MQ data. Reuse the correct existing boundary; do not wrap both in a new generic business client.
- Treat broker delivery as at least once unless the concrete system proves otherwise. Consumers need durable domain idempotency and must tolerate duplicate, delayed, and reordered messages.
- A database transaction does not include an external broker send. Search current source and production wiring before relying on an outbox/inbox; this repository must not be assumed to provide one universal implementation. When no suitable proven component exists and loss or double application is material, build a domain-owned durable intent/status record with lease or claim semantics, retry limits, dead-letter/manual disposition, reconciliation, and cleanup rather than relying on timing.
- Spring `ApplicationEvent` is process-local and usually not a durable cross-service boundary. Do not use it where a restart, another node, or service separation must be survived.
- Request-layer `@Idempotent` prevents a class of duplicate HTTP submissions; it is not a substitute for domain idempotency in jobs, callbacks, consumers, or retries.

## Files, WebSocket, and Notifications

- Store files through the infra module's `FileApi` and current upload/download contracts. Business modules keep only owned metadata and authorization; do not depend on `infra-server` or bind directly to an object-storage SDK.
- The current `FileApi` byte-array path requires an explicit, enforced size limit because the whole object is materialized in memory. For large objects, first verify and reuse a streaming, multipart, or presigned public capability; if that capability belongs to infra but is absent, extend its public contract instead of embedding an object-store client in each business module.
- Treat a direct or presigned object upload and the platform metadata registration as two separate outcomes. Keep an explicit pending/final/failed state or equivalent durable correlation, verify the uploaded object's owner-bound key, size/type and checksum where required before finalization, and define retry/compensation plus orphan cleanup. Read `references/security.md` for URL scope and download/export controls.
- Reuse the WebSocket starter's authenticated session and sender abstractions. Delivery is a notification, not the authoritative state transition. Persist important state first and let clients recover it through an authorized query after disconnect/reconnect.
- Avoid sending secrets, connection strings, unrestricted object-storage locations, or unauthorized payloads through logs or notifications. Validate user/application audience at the server boundary.

## Import, Export, and Audit

- Reuse the existing Excel utilities and established controller annotations. Imports need validation, duplicate/error reporting, bounded memory, and explicit partial-failure/transaction behavior. Exports enforce the same row-level scope as list APIs.
- Use API access logging for import/export and other established operation types. Evaluate business audit records for approvals, releases, overrides, permission changes, destructive operations, and other traceability requirements; do not add audit annotations mechanically to every CRUD call.
