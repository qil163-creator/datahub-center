# BPM and Workflow Integration

Read this reference for approval processes, process-instance creation/cancellation, callbacks/events, business-state transitions, and workflow reconciliation.

## Ownership Boundary

- BPM owns reusable process definitions and process-instance/task mechanics. The business module owns its aggregate, permissions, business state machine, snapshots, and the meaning of approve/reject/cancel.
- Outside `bpm-server`, use the public `bpm-api` contract. Do not depend on `bpm-server`, Flowable services, BPM data objects, or engine tables from another module.
- Do not move business approval states or domain-specific payloads into framework components to make the workflow look generic. Keep a thin adapter between the business aggregate and BPM DTO/event contracts.

## Starting and Tracking a Process

- Validate authorization, current state, required immutable snapshot, and uniqueness before requesting a process. Use a stable business key/correlation identity and persist the returned process-instance identity with guarded state.
- Local database state and BPM RPC are not one atomic transaction. Make repeated starts safe and define the recovery path for each split outcome: local record without process, process without final local update, timeout with unknown remote result, and cancellation after partial progress.
- For material workflows, provide a durable execution/correlation record and a reconciliation path that can query authoritative BPM state. Do not depend on a transient future, controller retry, or operator memory.

## Callbacks and State Transitions

- Keep in-process `ApplicationEvent` separate from a cross-process contract. A durable workflow event envelope needs a stable `eventId`, schema name/version, `occurredAt`, aggregate and process correlation, and a monotonically comparable revision or sequence so consumers can deduplicate and reject stale transitions.
- Treat completion/cancellation events and callbacks as duplicateable, delayed, or reordered. Deduplicate by event/process identity and guard every transition by allowed prior state, version/lock and aggregate identity.
- Never trust a client-supplied approval result, process-instance ID, tenant ID, or user ID as authorization. Reconcile it with the stored association and authoritative process state.
- Make terminal transitions idempotent. A late event must not reverse a newer terminal state or repeat side effects such as publication, deployment, notification, or billing.
- If transition side effects cross services or brokers, first search for an active, compatible durable delivery implementation; do not assume this repository has one universal outbox/inbox. Reuse a proven path or add domain-owned durable intent, retry and reconciliation according to risk. A listener transaction covers only its local database work.

## Operational Completion

- Define cancel/withdraw/reject/terminate semantics separately; they need not map to the same domain state.
- Expose enough correlation, last error, retry/reconcile status, and timestamps for operations without leaking engine internals or secrets.
- Test happy path, duplicate callback, stale/out-of-order callback, BPM timeout/unknown result, retry, authorization, object scope, and local/BPM disagreement for any workflow whose failure changes durable business state.
