# Scoped Verification

Read this reference after code or SQL changes and when reviewing whether verification is sufficient.

For a bug, regression, failing build/test, or intermittent runtime problem, read [debugging.md](debugging.md) first. Verification must exercise the original symptom or its evidence-backed deterministic model, not merely compile the edited files.

## General Strategy

1. Inspect the changed-file set and map it to affected modules, contracts, database behavior, and UI surfaces.
2. Run the narrowest check that exercises the changed behavior. For a bug fix, preserve failing-before and passing-after evidence when practical.
3. Expand to the affected module or application when compilation, shared APIs, configuration, routing, generated output, or integration behavior can propagate.
4. Run repository-wide checks only when the change crosses shared infrastructure or the user requests release-level verification.
5. Record exact commands, exit results, and skipped checks. Environment failures and skipped Testcontainers are not passing tests.

Do not rerun an unchanged passing check unless a later edit affects its surface, the test is intentionally repeated for concurrency/flakiness evidence, or a required final gate must be fresh. In the handoff, report the command, exit result, executed/skipped counts, and decisive failure lines rather than copying complete logs.

Do not run broad auto-fix or formatting commands in a dirty worktree. Limit formatting to files changed by the task and review the resulting diff.

Use repository-local deterministic gates instead of recreating their policy in ad hoc shell commands:

```powershell
python tools/quality/check_migrations.py
python tools/quality/check_error_codes.py
python tools/quality/check_module_dependencies.py
python tools/quality/run_all.py
```

Run the focused gate early when its surface changes and `run_all.py` before handoff. Normal delivery must keep migration immutability enabled; `--strict` is for deliberate historical-debt audits, not a substitute for understanding the default diff-aware result.

## Backend

- Read the affected artifact ID from its module POM and confirm the JVM used by Maven matches the current root POM requirement.
- For normal changes, compile/test with Maven reactor selection: `-pl ':<affected-artifact>' -am`.
- When selecting a single test across the reactor, add `-Dsurefire.failIfNoSpecifiedTests=false` so dependency modules without that test do not fail spuriously.
- Pure rules and conversions use focused JUnit tests; service orchestration may use Mockito or database tests according to behavior.
- Prefer the project's test bases by dependency surface: `BaseMockitoUnitTest` for pure/service-isolated logic, `BaseDbUnitTest` for generic Mapper/database behavior, `BaseRedisUnitTest` for Redis behavior, and `BaseDbAndRedisUnitTest` only when both are genuinely required. Do not load a larger Spring context by habit.
- Mapper SQL, PostgreSQL JSONB, partial indexes, locks, migrations, and replay behavior require real PostgreSQL/Testcontainers rather than H2 inference.
- When Antdv Next code-generation templates change, run `CodegenEngineVben5AntdvNextTest` and inspect representative generated output.
- A Testcontainers test marked `disabledWithoutDocker` or otherwise skipped can still leave Maven with exit code zero. Inspect Surefire executed/skipped counts and container logs before saying the PostgreSQL behavior passed.

## Java, Spring Boot, and Jackson Compatibility

- This repository currently targets Java 25 and Spring Boot 4, but resolve the effective values from the root POM, dependency-management POM, affected module, active Maven profiles, and the JVM reported by `mvn -version`. Do not declare compatibility merely because one property or IDE setting has the expected value.
- For root POM, BOM, starter, annotation-processor, or shared framework changes, inspect the effective POM and focused dependency tree. Detect conflicting Spring Boot/BOM versions, unexpected managed-version overrides, old Boot-specific starters, Jakarta/`javax` mismatches, and modules that override the compiler release.
- Treat Jackson 2 and Jackson 3 as distinct APIs and runtime ecosystems. Inspect both dependency coordinates and imported `ObjectMapper`/module types; do not assume a `com.fasterxml.jackson` module is registered in the Boot 4 mapper, or that similarly named serializers are assignment-compatible across packages.
- Verify a minimal Spring application context for affected auto-configuration/conditional beans, then test raw JSON bytes for the changed contract: safe and unsafe-range `Long`, default and annotated date/time fields, exact decimals, Java time modules, and any custom serializer/deserializer. Compilation alone cannot prove runtime auto-configuration or wire compatibility.
- If a compatibility shim is genuinely required, isolate it behind one owned boundary, test both sides, and avoid leaking two Jackson type families through a public component API.

## Shared Components

- Test a changed framework component in its own Maven module first, then compile or test every direct consumer affected by the current change. A component unit test alone does not prove consumer wiring or backward compatibility.
- For DB Switch, add focused golden-output tests per affected dialect and use the real target database/Testcontainers for metadata, DDL execution, native types, transactions, locking, bulk writes, or migration behavior. H2 and string inspection cannot establish those contracts.
- Test public API/SPI compatibility, default behavior when new configuration is absent, auto-configuration and conditional beans where applicable, and failure/cleanup paths for connections, transactions, threads, and callbacks.
- When retaining a thin business adapter, test the domain-to-component mapping and orchestration boundary; do not duplicate component behavior in adapter tests.

## Frontend

- Read commands from the current root and `apps/web-antdv-next/package.json`.
- At minimum for TypeScript/Vue changes, run the active app typecheck and lint the changed surface. Add focused Vitest for changed logic or regressions.
- Run the Antdv Next build for routing, dependency, build-configuration, code-splitting, or delivery-risk changes.
- A Vite build does not replace typecheck.
- Do not run repository-wide formatting or a lint hook containing `--fix` by default in a dirty worktree. Run non-mutating checks against the task's files.

Current command shapes to confirm from package scripts before running are:

```powershell
pnpm --filter @vben/web-antdv-next run typecheck
pnpm exec vitest run <test-file>
pnpm run build:antdv-next
pnpm exec oxlint --type-aware --threads=4 --no-error-on-unmatched-pattern <files>
pnpm exec eslint <files>
pnpm exec stylelint <vue-or-style-files>
```

Changes to shared packages, adapters, routing, request infrastructure, or build configuration require checking their direct consumers in addition to the active app.

## Runtime and Workflow

- For jobs, test parameter validation, duplicate/retry idempotency, overlapping execution, execution without tenant context, failure reporting, and recovery. Do not prove a distributed job solely by calling a private service method once.
- For MQ/events, test duplicate, delayed and reordered delivery, poison/failure handling, and local-state/message split outcomes according to risk.
- For BPM, test guarded transitions, duplicate/stale callbacks, timeout with unknown outcome, retry/reconciliation, authorization, object scope, and local/process disagreement.
- For async context, verify the required security/tracing context explicitly; a test running on the caller thread does not prove executor propagation.

## Database

- Before creating a file, run `python tools/quality/check_migrations.py --next` from the backend root (or add `--root <backend-root>`) and use only its successful four-digit result. Before handoff run `python tools/quality/check_migrations.py`; the repository gate is authoritative for structural sequencing, the baseline marker, dependency references, and immutable bytes.
- Verify the new numbered file sorts after every existing migration in `sql/postgresql/migrations` and has no duplicate four-digit prefix.
- Check the declared prerequisite and execution order against actual object/data dependencies.
- If marked repeatable, execute it twice against PostgreSQL in a disposable database when practical.
- When `datahub-center.sql` changes, verify fresh baseline behavior separately from existing-database migration behavior.
- Check permission/menu SQL against backend annotations and frontend authorization strings.

## Final Report

Summarize:

- changed files and purpose;
- commands actually executed and their results;
- checks not executed and why;
- remaining risks;
- for SQL changes, the mandatory `SQL 执行顺序` with exact paths and prerequisites.
