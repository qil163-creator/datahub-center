# Code Generation Workflow

Read this reference before using the infra generator, integrating its output, or changing its templates. The generator is appropriate for conventional single-table, tree, and supported master-detail CRUD; hand-code workflows, state machines, ETL, visual editors, complex authorization, and concurrency-sensitive behavior.

## Generate From Current Facts

1. Identify the owning backend module and active frontend application. Inspect current codegen configuration, enums, templates, tests, and a mature generated feature; never hardcode a front-type number remembered from documentation.
2. If generation requires a new platform table, first read `references/database.md`, create the next ordered structure migration, and update the fresh-install baseline when applicable. Import/apply it only to an authorized local or disposable PostgreSQL database for generation; creating SQL does not authorize execution against a shared/live environment.
3. Configure table/business names, module/package, scene, parent menu, permissions, and fields from the current requirement. Preview all outputs before writing files.
4. Integrate selectively into the actual module and `apps/web-antdv-next/src` layout. Verify generated archive paths: an older Vben path or repository-root `src/**` path must not dictate the destination of the active app.
5. Review the final diff as hand-written production code and add behavior-focused tests. Do not keep placeholders merely because the generator emitted them.

## Mandatory Generated-Output Review

- Architecture: correct domain owner, `api/server` boundary, package names, dependency direction, no duplicate framework capability.
- Backend API: separate Req/Resp VO shapes, validation, `@Tag`/`@Operation`, `CommonResult`, error-code merge, no DO exposure. Master-detail templates require special review because subtable request/response paths may expose DOs.
- Business rules: existence/uniqueness/reference checks, state/concurrency guards, transactions, department/object data scope, import/export scope, audit requirements.
- Frontend contract: `Long` IDs remain lossless; exact decimals are not reduced to imprecise JavaScript numbers; dates match actual JSON; create/update/response shapes and every API return type are explicit; paging and array query serialization are correct.
- Antdv Next: imports use active adapters/components, not legacy UI packages or unsupported deep imports; generated General/Schema layout matches current configuration rather than a remembered preference.
- Routing/security: resource paths omit duplicate `/admin-api`, dynamic menu component resolves to `views/**`, and backend annotation, menu SQL, and frontend permission code match exactly.
- Persistence: constraints/indexes/comments/audit/delete behavior（DataHub 不生成租户字段） are complete and Mapper queries match them.

Current template behavior must be rechecked whenever it matters. At the latest audit, Antdv Next reused API templates that typed `Long`/exact decimals as `number`, typed dates as `string | Dayjs`, reused one entity shape for requests and responses, and left some returns/queries broad. Typecheck alone cannot prove these runtime contracts safe, so generated types always receive the review above.

## Generated SQL Is Input, Not a Migration

- Do not submit or execute an unnumbered generated artifact such as `sql/sql.sql` as the deployment change.
- Move and reconcile its complete menu/permission/dictionary/data delta into the next global `NNNN_<module>_<short-description>.sql` migration. If structure and menu/data are dependency-ordered files, allocate consecutive numbers and state the dependency.
- Do not duplicate table DDL already supplied by the structure migration. Synchronize `datahub-center.sql` for a fresh install when the final platform state belongs there.
- Run the migration sequence checker before allocation and before handoff, then list every SQL file in `SQL 执行顺序`.

## Template Changes

When a template or generator path algorithm changes:

- add or update fixtures/assertions for the bug class, including Long IDs, exact decimals, date/time transport, Req/Resp separation, return generics, array queries, imports, and active-app output paths as applicable;
- run the focused `CodegenEngineVben5AntdvNextTest` through the infra server Maven reactor with `-Dsurefire.failIfNoSpecifiedTests=false`;
- inspect representative generated backend, frontend, and SQL output instead of relying only on snapshot success;
- typecheck/build the active frontend and compile/test affected backend consumers according to `references/testing.md`.

Generated unit tests may be disabled in current configuration. That means tests must be selected by behavior and risk; it does not mean the feature is exempt from tests.
