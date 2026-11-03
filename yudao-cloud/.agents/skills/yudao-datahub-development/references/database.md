# Ordered PostgreSQL Migrations

Read this reference before any task adds or changes PostgreSQL schema, indexes, constraints, sequences, dictionaries, menu permissions, seed data, or correction data.

## Required Deliverables

Every task that changes the platform PostgreSQL database state released and managed by this repository must create a new, independently executable incremental SQL file under `sql/postgresql/migrations`. Updating Java mappings or the full `datahub-center.sql` snapshot alone is insufficient.

Within the same unshipped task, revise the migration created by that task as the implementation evolves; do not allocate a new number for every edit. Once a migration has been shared, delivered, merged, released, or possibly executed, the immutability rules below apply and every correction uses a later number.

When the final state belongs in a fresh installation, also update `sql/postgresql/datahub-center.sql`. The baseline is for a new database; the new numbered migration is for an existing database. Never instruct an existing environment to re-import the complete baseline.

When numbered migrations exist, keep exactly one marker near the beginning of the full baseline:

```sql
-- Included migrations through: NNNN
```

The value must equal the highest numbered migration and means that a database created from this snapshot already contains those changes. It is not evidence that an existing database ran them. At initial adoption, document the cutover state and preflight legacy objects/data before treating an existing environment as ready for `0001`; do not infer readiness from a branch name, deployment date, or the presence of one table.

## Runtime Database Capability Versus Repository Migration

- Runtime DDL generated or executed against an external/target datasource for a user task does not by itself require a platform repository migration. Use DB Switch for that dynamic operation. If the same feature changes platform-owned task tables, dictionaries, menus, or other persisted state, those platform changes still require numbered migrations.
- Use the active DB Switch component first for reusable runtime database mechanics: metadata discovery, product/dialect selection, create-table DDL, database operators and writers, and runtime full/incremental data movement. Search and verify its existing identifier and type behavior; when semantics are insufficient, extend a public dialect/type contract in DB Switch rather than adding another business-local implementation. Read `references/dbswitch.md` for the capability and extension boundary.
- If a generic database mechanism is missing, assess and normally add it to DB Switch with dialect and integration tests before consuming it from a business module. Keep business approval, version state, authorization, datasource-record ownership, scheduling, and domain seed decisions outside DB Switch.
- DB Switch runtime migration or auto-DDL is not a repository schema-versioning system. It never replaces the numbered SQL file required by this reference, nor the fresh-install baseline when that baseline must change.
- A task may therefore require both: reusable Java behavior in DB Switch and an ordered `NNNN_*.sql` migration for deployment. Verify and report the two paths separately.

## Global Sequence and Filename

Use this filename form:

```text
NNNN_<module>_<short-description>.sql
```

Examples:

```text
0001_governance_standard-table.sql
0002_governance_standard-dictionary.sql
0003_governance_standard-menu.sql
0010_system_permission-fix.sql
```

Rules:

1. Keep only numbered production migration `.sql` files in this directory. Generated, scratch, baseline, self-check, and test-fixture SQL belongs elsewhere; the sequence checker rejects unnumbered `.sql` here.
2. Use exactly four ASCII digits so lexicographic filename order equals execution order: `0001`, `0002`, ..., `0010`.
3. Before creating a migration, scan direct SQL files in `sql/postgresql/migrations` whose names start with `^[0-9]{4}_`. The next number is the maximum leading prefix plus one; if the directory or numbered files do not exist, start at `0001`.
4. The number is global for this directory, not per module or feature. Never restart a module at `0001`.
5. Multiple dependent migrations in one task use consecutive numbers in actual dependency order. Derive that order from object and data prerequisites rather than a universal phase list: expansion precedes statements that use new objects; backfill precedes a new `NOT NULL`/unique constraint; parent dictionary/menu rows precede children and grants. Each later file names its direct earlier migration in `Depends on`.
6. Before merge or delivery, rescan for parallel-branch collisions. Renumber unexecuted current-task files to the new maximum plus one and update dependency headers and delivery instructions.
7. Do not reuse an earlier number. A gap is safer than changing the identity of a migration that may have been shared.

Use the repository-local quality gate as the single authority before allocating a number and again immediately before handoff:

```powershell
python tools/quality/check_migrations.py --next
python tools/quality/check_migrations.py
```

Run these from the backend root, or pass `--root <backend-root>`. `--next` validates the current chain before printing the next global four-digit prefix; it must not allocate through an existing structural error. The normal check validates filenames, duplicate prefixes, required headers, dependency direction, the baseline marker, and exact-byte immutability against its Git base. Do not use `--no-immutability` for delivery or release checks. This remains a structural guard: it does not parse PostgreSQL semantics, inspect a live database, or prove that a migration is safe to execute.

Legacy files in the `sql/postgresql` root keep their existing names and paths. Do not retro-number, bulk move, rename, or silently fold them into the new chain because tests and operational documents may refer to those paths and deployed environments may have applied different subsets. They are outside the new sequence; if a new migration depends on one, name that exact legacy file or a verified legacy baseline state in both the SQL header and the final execution order.

## Immutability

- Treat a merged, released, delivered, or possibly executed migration as immutable.
- Correct it with the next numbered migration rather than modifying it in place.
- A newly created migration may be edited only while it is still confined to the current, unshipped task.
- Do not squash several already delivered migrations into a new historical order.

## File Header

Start every numbered file with a concise header containing at least:

```sql
-- Migration: 0001 Governance standard table
-- Purpose: <what state this establishes>
-- Depends on: <previous numbered or exact legacy script, or baseline state>
-- Target: existing PostgreSQL database
-- Repeatable: yes/no; <reason or guard strategy>
-- Rollback: <exact rollback file/steps, or why the operation is intentionally irreversible>
```

Keep the header truthful. Do not claim repeatability when an unguarded DDL or DML statement will fail or duplicate data on a second run. The `Migration` value must begin with the same four-digit prefix as the filename; it may also repeat the complete filename.

## SQL Design

- Prefer additive and replay-safe PostgreSQL constructs such as `IF NOT EXISTS`, guarded DML, conflict handling, and existence checks where semantics permit them.
- Include the complete executable delta: sequences, tables, comments, indexes, constraints, required dictionaries, menus, permissions, and backfills that belong to this migration. Do not leave manual SQL only in prose.
- 保留审计和逻辑删除列。DataHub `dh_*` 表不含租户字段；唯一键、外键和索引直接表达业务约束。脚手架表与外部引擎 schema 按各自契约维护。删除列时检查索引、约束、视图、触发器和函数体，并使用真实 PostgreSQL 验证。
- Use transactions when every statement is valid and safe in the same PostgreSQL transaction. If an operation cannot or should not run transactionally, state the execution boundary and recovery procedure in the header.
- For destructive DDL, large backfills, long locks, or irreversible transformations, explain impact and obtain any authorization required by the task before execution. Creating the migration file does not authorize running it against a live database.
- Keep backend permission annotations, menu/button permission strings, dictionary values, and frontend permission use aligned with SQL seed data.

## Verification and Handoff

- Run `python tools/quality/check_migrations.py` after the final collision rescan, then run `python tools/quality/run_all.py` for the repository-wide deterministic gates. Fix structural errors before delivery; do not treat a successful gate as SQL execution.
- Parse or execute the migration against PostgreSQL/Testcontainers when practical. Test both first execution and replay when the file claims repeatability.
- Verify the fresh-install baseline reaches the same required final schema when the baseline is updated.
- Do not state that SQL passed if it was only visually inspected or the database test was skipped.
- In every final response involving SQL, add a section titled `SQL 执行顺序`. List exact file paths in ascending sequence, identify whether each is for a fresh or existing database, and name all legacy prerequisites. Include the section even for a single file.
