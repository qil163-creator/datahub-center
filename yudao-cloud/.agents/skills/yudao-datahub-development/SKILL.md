---
name: yudao-datahub-development
description: Develop, diagnose, fix, or review changes in the paired yudao-cloud and yudao-ui-admin-vben DataHub repositories. Enforce module ownership, component-first and DB Switch reuse, Yudao/Antdv Next contracts, lossless IDs and dates, ordered PostgreSQL migrations, generator review, workflows, runtime integrations, systematic debugging, and risk-scoped verification. Use for features, bug investigation and fixes, refactors, CRUD, SQL, code generation, tests, and code review in these repositories; do not use for unrelated projects or prose-only planning.
---

# Yudao DataHub Development

Use this skill for implementation, debugging, and review work in the paired backend `yudao-cloud` and frontend `yudao-ui-admin-vben` repositories.

## 单租户产品边界

- DataHub 是单租户平台；`asset`、`dataforge`、`governance`、`modeling`、`supply` 的业务代码和 `dh_*` 表不引入 `tenant_id`、`tenantId`、`TenantBaseDO`、租户过滤、逐租户任务或前端租户切换依赖。不得用固定租户编号作为业务兼容层。
- 保留脚手架 `system`、`infra`、`bpm` 与 framework 的通用多租户能力及关闭开关；维护通用组件时仍须验证其已有模式。外部 DolphinScheduler 的执行租户等产品概念不等于平台租户，按外部协议保留。
- 单租户不取消登录、菜单权限、部门数据范围、对象授权、应用签名、防重放或审计。业务事件、缓存、锁、唯一键和任务按实际业务对象与用户/应用范围设计。
- 本平台尚未上线，不为已移除的业务租户契约保留过渡接口；平台数据库变更仍遵守编号迁移、最终基线和真实 PostgreSQL 验证规则。

## Establish Context

1. Locate the relevant repository root from its manifest rather than assuming an absolute machine path:
   - backend: root `pom.xml` plus `yudao-module-*` directories;
   - frontend: root `package.json` plus `apps/web-antdv-next`.
2. For every target file, walk from its directory toward the repository root and read the nearest applicable `AGENTS.md` before changing anything; combine it with the repository-root rules. For a cross-repository task, do this in both repositories.
3. Inspect `git status` and preserve user changes. Treat untracked or currently modified implementations as work in progress unless the user identifies them as authoritative.
4. Keep the user's requested scope. Do not add counterpart changes merely because both repositories are available; do make both sides consistent when the requested feature actually crosses the API boundary.

## Use Current Evidence

Resolve conflicts in this order:

1. the user's explicit request;
2. executable configuration, current source, generated templates, and tests;
3. project-specific root README;
4. generic repository documentation;
5. historical examples or statements preserved in this skill.

Read one or two mature, tracked examples from the target module before implementing. Prefer framework bases and active code-generator templates over old scaffold leftovers. Do not copy version numbers, ports, credentials, permission strings, DTO fields, or local paths from this skill; obtain them from the current repositories.

## Route the Task

- For module ownership, new-module decisions, `api/server` boundaries, internal RPC, and dependency direction, read [references/architecture.md](references/architecture.md).
- For Java, Maven, service boundaries, controllers, services, DOs, mappers, error codes, tenancy, and authorization, read [references/backend.md](references/backend.md).
- For any backend technical helper, executor, factory, parser, converter, database operation, infrastructure integration, or framework change, also read [references/components.md](references/components.md).
- For database metadata, dialects, identifiers, types, DDL, readers/writers, or runtime data movement, also read [references/dbswitch.md](references/dbswitch.md).
- For Vue, TypeScript, API clients, forms, tables, modals, routes, and UI permissions, read [references/frontend.md](references/frontend.md).
- For an API or feature crossing both repositories, menus, permissions, or DTO alignment, read [references/fullstack.md](references/fullstack.md).
- For generating or modifying generated backend/frontend code or templates, read [references/codegen.md](references/codegen.md).
- For approvals, process instances, BPM callbacks, workflow state, or reconciliation, read [references/workflow.md](references/workflow.md).
- For jobs, asynchronous execution, MQ/events, files, WebSocket delivery, import/export, or audit logging, read [references/runtime.md](references/runtime.md).
- For authentication/authorization boundaries, internal RPC headers, open APIs, dynamic SQL, external databases or storage, uploads/downloads, exports, secrets, or sensitive logging, read [references/security.md](references/security.md).
- For any schema, index, constraint, sequence, seed, dictionary, menu, permission, or data-correction SQL, read [references/database.md](references/database.md) before editing.
- For any bug, regression, failing test/build, unexpected data, timeout, race, performance problem, or integration failure, read [references/debugging.md](references/debugging.md) before proposing a permanent fix.
- Whenever code or SQL changes, or when reviewing verification sufficiency, read [references/testing.md](references/testing.md).
- When maintaining or behaviorally evaluating this Skill itself, read [references/skill-evaluation.md](references/skill-evaluation.md).

Load only the references required by the task. A backend-only fix with no database or contract change does not need the frontend, full-stack, or database reference.

For debugging, start with the fast path in `references/debugging.md`. Within repository evidence, search exact errors and symbols first, inspect tight code/log ranges and direct callers, reuse facts already captured, and expand one boundary at a time only when the next decision requires it. Token and latency savings never replace root-cause or fresh-verification evidence.

## Implement Deliberately

- 新编写或修改的代码如有注释，注释的说明文字必须使用中文，包括行内注释、块注释、JavaDoc/TSDoc 和 SQL 注释；技术名称、代码标识符及工具指令保留原文。仅处理本次新增或修改代码相关的注释，不要求为无注释代码补写注释，也不批量翻译无关历史注释。
- Before adding a reusable technical class or mechanism to a business module, search active framework starters, module APIs, production callers, and tests. Reuse an existing public API or SPI when possible; when a missing generic capability belongs to a component, extend and test that component first. Keep domain policy and orchestration in the business module, and record the search and ownership decision in the handoff or review.
- For ordinary single-table, tree, or master-detail CRUD, evaluate the existing infra code generator first. Preview its output and review it; do not assume generation is mandatory or complete.
- Hand-code complex orchestration, ETL, state machines, permission decisions, concurrency, and security-sensitive flows using mature neighboring implementations.
- When adding an `@XxlJob` handler, add a row to `docs/jobs/xxl-job-tasks.md` with its executor AppName, exact JobHandler, purpose, and Cron in the existing four-column format. Label a proposed Cron as a recommendation rather than an active configuration; update the row when a handler or its schedule changes.
- Before changing an interface, explicitly determine permissions and department/object data scope, transaction/concurrency invariants, SQL migration needs, menu changes, and frontend contract impact.
- Make the smallest coherent change. Do not modernize unrelated historical code or apply new conventions to the entire repository.

## Finish With Evidence

- Select checks by affected surface and risk as described in `references/testing.md`.
- Treat the repository-local `tools/quality` checks as the authority for deterministic migration, error-code, and module-boundary policy; do not replace them with copied Skill-local validators.
- For bug fixes, report the original reproducer or evidence package, the root-cause chain, why the fix belongs in the chosen layer, and fresh regression evidence. Label containment or mitigation honestly; do not call it root-cause resolution.
- Report only checks actually run. State skipped or environment-blocked checks explicitly.
- If SQL is involved, always include an `SQL 执行顺序` section in the final response with exact numbered file paths, target database state, and prerequisites, even when there is only one migration.
- Treat explicit user direction as higher priority than this skill. If it departs from a repository invariant, explain the concrete impact instead of silently expanding or rewriting the request.
