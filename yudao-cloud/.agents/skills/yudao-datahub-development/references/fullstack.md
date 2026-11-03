# Full-Stack Contract

Read this reference when a feature, fix, review, or generated CRUD crosses backend and frontend boundaries.

## Contract Checklist

Before editing, identify the owning backend module and target frontend domain. Then verify all affected items:

- controller resource path and frontend API path;
- HTTP method and parameter placement;
- Save/Page/Resp VO fields, nullability, validation, and frontend request/response types;
- lossless `Long` identifier transport: responses can be JSON `number` or decimal `string`, and routes/UI must not coerce them through JavaScript `Number`;
- date/time representation from the actual VO and `@JsonFormat`: do not type default epoch-millisecond `LocalDateTime` values as `Dayjs` or an assumed formatted string;
- exact decimals, enums, dictionary value types, and JSON structures; when precision matters, confirm raw backend JSON is a decimal string and keep it lossless through frontend display, calculation, edit, and submission;
- pagination `pageNo/pageSize` and `list/total`;
- backend `@PreAuthorize`, RBAC/menu SQL, and frontend action authorization;
- backend menu component path and actual `views/**` entry file;
- single-tenant platform plus department/object/business data scope;
- export behavior and row-level authorization;
- error codes and user-visible error handling.

The frontend request layer supplies `/admin-api` through the gateway configuration; neither side should duplicate it in resource paths.

Classify every changed endpoint as browser/admin, authenticated internal RPC, or public/open API. Those surfaces have different trust, header, replay, and authorization rules; read `references/security.md` instead of copying an adjacent endpoint across the boundary.

## Code Generation

Read `references/codegen.md` before generating code, accepting generated files, or changing generator templates. Generated output is a candidate scaffold, never evidence that DTO, permission, SQL, ID/date, or active-app-path contracts are correct.

## Cross-Service Changes

- Add or change public module API contracts in the owning `*-api` artifact and implementations in `*-server`.
- Update Feign/RPC registration and consumers only when the contract requires it.
- Keep cross-service reads and writes behind the provider's `*-api` contract or a formal message/event contract; a shared database does not authorize direct writes to another service's tables. Choose synchronous RPC or asynchronous delivery from required outcome, latency, availability and recovery semantics, then define idempotency, unknown-result handling and reconciliation for that choice.

## Completion Review

For a new admin capability, explicitly decide whether the change needs each of the following; do not create placeholders for items that are not required:

- backend API and service logic;
- error code;
- table/index/constraint/data migration;
- dictionary or menu/permission seed;
- frontend API/types and view;
- dynamic menu component mapping;
- authorization and data-scope tests;
- focused backend and frontend verification.
