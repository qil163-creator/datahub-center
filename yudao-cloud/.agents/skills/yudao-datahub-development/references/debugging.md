# Systematic Debugging and Bug Fixing

Read this reference for bug reports, regressions, failing builds or tests, incorrect data, intermittent behavior, performance degradation, and integration failures.

## Establish the Authorized Outcome

- Distinguish a diagnosis request from a fix request. Diagnosis authorizes read-only investigation and a cause report, not implementation. A request to fix authorizes the smallest coherent correction and its verification.
- Preserve the original failure evidence before editing. Do not overwrite logs, test fixtures, SQL state, or user work that may explain the defect.
- Do not make speculative production changes, execute destructive SQL, mutate a customer database, replay a live message, or call a state-changing external endpoint merely to reproduce a bug without explicit authority.
- A permanent fix requires an evidence-backed root-cause hypothesis. Emergency containment may precede full root-cause work when the user requests it or safety requires it, but label the action as mitigation, state its trade-offs, and continue the investigation when in scope.

## Fast Path and Investigation Budget

Optimize for the fewest high-information actions, not the fewest proofs. Use this fast path when the failure is deterministic and locally scoped, an existing command or test exposes it, the likely owner is clear, and the experiment is reversible without production, security, tenant, database, durable-message, or customer-data risk:

1. Capture the exact failing command or request and only the decisive error/cause excerpt.
2. Use `rg` to locate the named symbol, configuration, and direct caller/callee; inspect the smallest relevant ranges and one working neighbor.
3. State one root-cause hypothesis and discriminating prediction, then run the smallest focused test or read-only probe.
4. If confirmed, make one minimal fix in the owning layer.
5. Re-run the original reproducer and the nearest required gate. Stop when both prove the fix and no changed shared contract or unresolved evidence requires wider checks.

Escalate to the full phases below when reproduction is unstable, the first hypothesis is disproved, ownership is unclear, evidence crosses a frontend/backend or service boundary, or the problem involves shared components, DB Switch, SQL/migrations, tenant/security, concurrency, caches, external systems, durable state, performance, or production-only behavior.

Keep investigation context compact:

- Maintain one evidence ledger with symptom, decisive observations, current hypothesis, experiment, result, and artifact path/line. Update it instead of retelling the investigation.
- Cite exact paths, symbols, line ranges, correlation IDs, and commands; summarize decisive output. Do not paste entire files, stack traces, dependency trees, or logs when a small excerpt and retained artifact establish the fact.
- Reuse already captured results. Do not reread unchanged files or rerun a passing command unless a later edit touched its surface, the result is stale, or repetition is itself the flakiness experiment.
- Batch independent read-only searches or checks when doing so reduces latency. Keep hypothesis-changing experiments and code edits serial so causality remains observable.
- Choose the next check by highest expected information gain at the lowest safe cost. Expand from symbol → layer → module → cross-boundary system; do not start with a repository-wide audit.
- End the investigation when the root cause is confirmed, the owning layer is identified, the original symptom and affected contract are freshly verified, and no material uncertainty remains. Do not continue unrelated cleanup or speculative hardening.

## Phase 1: Capture and Triage

1. Record expected behavior, actual behavior, impact, exact operation or command, first known occurrence, frequency, and whether the failure is deterministic.
2. Capture the complete error and causal chain, including the first meaningful exception rather than only its outer wrapper. Record relevant HTTP status, business error code, SQLSTATE/vendor code, failed assertion, or build phase.
3. Record the smallest useful context matrix: repository revision and dirty state, runtime/profile, user role and data scope, request/trace/job/task identifier, database product and version, browser when relevant, and safe input characteristics. At remote or asynchronous boundaries, compare the actual propagated security/environment context with its authoritative source.
4. Inspect current diffs, recent relevant commits, dependency or configuration changes, and environmental differences. Treat temporal correlation as a lead, not proof.
5. For intermittent or production-only failures, preserve telemetry first and define what additional observation would distinguish competing causes. Do not turn lack of reproduction into permission to guess.

Redact passwords, tokens, cookies, authorization headers, connection strings, personal data, and customer payloads. Diagnostic logging must be narrowly scoped, removable or operationally justified, and safe for the target environment.

## Phase 2: Reproduce and Localize

- Build the smallest safe reproducer: one focused test, request, fixture, command, or disposable-database case. Reduce inputs while retaining the same failure mechanism.
- Find a mature working neighbor in the same repository and compare contracts, configuration, ownership, lifecycle, and dependencies. List meaningful differences before choosing one as causal.
- Divide the path at observable boundaries and identify the first boundary where actual output diverges from expected output. Capture inputs and outputs on both sides of that boundary instead of adding logs everywhere.
- When the error appears deep in the stack, trace the bad value, state, or decision backward through callers and transformations until reaching its first invalid origin. The throw site is often only the detection point.
- Use `git diff`, `git log`, and `git show` as read-only evidence. Use `git bisect` only with known good/bad observations in a clean disposable worktree; never let bisection overwrite the user's dirty checkout.

Use this project-specific boundary map:

| Surface | Boundaries and evidence to inspect | Ownership and decisive proof |
| --- | --- | --- |
| Frontend behavior | user input → component/form state → request params and raw response → store/cache → render | Frontend unless the wire contract is wrong; reproduce with focused Vitest and inspect actual network payloads, including mixed `Long`, exact decimal, and date representations. |
| Backend request | gateway/security context → Controller ReqVO → Service invariants → component/Mapper → stored result | Fix the first owning layer that violates its contract; prove with focused JUnit plus database or context tests when behavior is not pure logic. |
| DB Switch | business adapter → public API/SPI → factory/operator/dialect/type mapping → driver/database | Generic database defects belong in DB Switch; build a product-by-operation evidence matrix and verify generated output plus the real affected database behavior. |
| Platform schema/data | numbered migration prerequisite → schema/data state → application query → fresh baseline | Deliver a new ordered migration for platform changes and test it on disposable PostgreSQL; never repair an executed migration in place or confuse runtime dynamic DDL with platform migration. |
| RPC, MQ, jobs, BPM | local transaction and owned security/context → outbound headers/message → remote/broker state → consumer/callback → reconciliation | Build a timestamped correlation and idempotency timeline across boundaries; verify propagated context rather than assuming an interceptor supplied it, then test duplicate, delay, reorder, retry, timeout, and unknown outcome as applicable. |
| Authorization | authenticated principal → permission → data scope/object Guard → query/write | 由后端执行授权，包含跨用户/对象的拒绝路径，不能依赖隐藏按钮。 |
| Build/runtime wiring | effective POM/package scripts → dependency graph → compile/typecheck → application context → serialized bytes | Compilation is only one boundary; use effective dependencies, focused startup, and raw contract output to prove runtime wiring. |

For backend localization, use these fast diagnostic branches without bypassing the owning references:

- For Maven, dependency, or startup failures, first record `mvn -version`, effective POM/profile and dependency tree; then inspect auto-configuration imports, conditions, and the actual runtime context. Do not diagnose a project compatibility failure under the wrong JDK or add a bean/dependency before proving what is absent.
- When `@Transactional`, tenant/data-permission, cache, job, or async behavior appears ineffective, prove that the object is Spring-managed, the call crosses the intended proxy, and the work remains on a supported thread. Record the actual proxy/bean, transaction manager and active/synchronization state; check self-invocation, manual construction, async dispatch, propagation, and after-commit timing.
- DataHub 诊断应证明无租户上下文也能执行，并分别验证登录、部门/对象权限和实际 SQL；不得关闭数据权限隐藏故障。通用脚手架租户组件的任务才检查租户传播和隔离。
- For Feign, record caller context, propagated headers, environment tag and selected instance, HTTP status, `CommonResult` code, and caller exception. A transport success is not proof of business success, and a response from the wrong instance can be syntactically valid.
- Identify the cache plane before changing it: Spring Cache, direct Redis, or local cache. Resolve the physical namespace/key, tenant scoping, invalidation owner, transaction timing, and stale-value lifecycle; do not flush all caches or disable caching to hide an ownership defect.
- 对 MQ、XXL-Job、BPM 核对实际触发源、消费重试、持久执行状态、执行人、幂等键与最终状态；验证恢复的业务/流程关联，不假设存在租户上下文。

For frontend localization, follow [frontend.md](frontend.md) and classify the first divergence among request unwrapping, raw wire types, async state/cache lifetime, backend-delivered route resolution, the installed Antdv Next adapter contract, and multi-stage upload finalization. Diagnose the actual payload, runtime field type, request completion order, cache key/lifetime, component export, and transfer/finalization result before adding a page-local workaround or type assertion.

## Phase 3: Form and Test One Hypothesis

For each attempt, state:

- **Observation:** the evidence that needs explanation;
- **Hypothesis:** one specific root cause and why it explains the observation;
- **Prediction:** what must differ if the hypothesis is true;
- **Experiment:** the smallest safe check that distinguishes it from alternatives;
- **Result:** confirmed, disproved, or still inconclusive.

Change one variable at a time. Prefer a read-only probe or focused test over a production-code edit. When evidence disproves a hypothesis, remove temporary probes or experimental edits, retain the useful observation, and form a new hypothesis rather than stacking another patch on top.

After repeated unsuccessful patches—normally two or three—or when every attempt reveals wider shared state, coupling, or new symptoms, stop editing and reopen the ownership and architecture assumptions. This is a review trigger, not automatic proof that the architecture is wrong; expand scope or request a design decision only when the evidence requires it.

## Phase 4: Implement the Root-Cause Fix

- Before the permanent edit, create the smallest regression test that fails for the observed reason when practical. If an automated failing test is infeasible, retain a repeatable manual reproducer or bounded diagnostic probe and explain the limitation.
- Fix the earliest layer that owns the violated invariant, not merely the layer that reports the error. Do not use a business-module helper to hide a missing DB Switch or other framework capability.
- If the root cause is in a shared component, fix or extend its stable public API/SPI and tests first, then keep the business adapter thin and verify affected consumers.
- Add defense at independent contract boundaries only when each guard owns a real invariant, such as ReqVO shape, Service domain state, database constraint, or external-resource safety. Do not duplicate the same policy indiscriminately across every layer.
- Keep the patch causal and reviewable. Separate unrelated cleanup, modernization, and broad refactoring from the bug fix.
- A timeout, retry, fallback, broad exception catch, null default, or cache bypass is not a root-cause fix unless evidence shows it is the correct contract. For distributed unknown outcomes, pair retry with idempotency and reconciliation.
- If the fix changes platform PostgreSQL state, follow the numbered migration rules. If it changes dynamic database execution, reuse or extend DB Switch and verify each affected dialect.
- Before a data correction, run a read-only impact query, define deterministic selection and postconditions, and state batching, lock, replay, and ambiguity behavior. Distinguish transaction rollback, a new numbered forward-fix, and business compensation; do not promise a generic down migration for irreversible data loss.

## Phase 5: Prove the Result

1. Re-run the original reproducer and show that the symptom is gone. When practical, preserve red-before/green-after evidence that the regression test failed for the intended reason before the fix.
2. Run the focused regression test, then checks for the owning module/component and affected direct consumers. Add adjacent negative and boundary cases where the root cause could recur.
3. For races or flaky behavior, replace guessed sleeps with waiting for an observable condition plus a bounded timeout. Frontend tests should deliberately invert two request completion orders and prove an older request cannot overwrite the current data, error, or loading state; cover cold/hot cache and identity changes when relevant. Run enough repetitions and parallel/contended variants to make the evidence meaningful; state the run count.
4. For PostgreSQL, driver, dialect, transaction, lock, JSONB, or migration behavior, use the real database/Testcontainers. H2 or SQL string inspection alone is not proof.
5. For frontend/backend contracts, inspect raw serialized values and actual request/response shapes. Do not normalize large IDs or dates into a representation that merely makes the test pass.
6. Inspect test execution and skip counts, exit codes, relevant logs, and the final diff. Classify repository-wide failures against a before-change or otherwise comparable baseline: an existing failure is not proof of a new regression, but it also does not excuse a failure in the changed surface. Remove temporary diagnostics unless they are deliberately retained with redaction, level, and operational purpose.
7. Run the repository-local quality gates and the risk-scoped commands from [testing.md](testing.md). Fresh evidence must support each completion claim; a passing build does not prove an unexercised symptom.
8. Once the original reproducer, owning component/module, required direct consumers, and mandatory policy gates pass, broaden or repeat checks only when later edits, shared-surface risk, flakiness, or unresolved evidence justifies it.

Never revert or overwrite unrelated user changes merely to demonstrate a red/green cycle. Use a test written before the fix, a disposable worktree, or an explicitly documented limitation.

## Shortcuts That Are Not Fixes

- Random edits, multiple simultaneous fixes, or copying a nearby pattern without understanding its dependencies.
- Catching and suppressing an exception, returning empty data, relaxing validation, or converting an error into a misleading success response.
- Increasing retries, timeouts, pool sizes, or arbitrary sleeps without showing which resource, condition, or contract is responsible.
- Disabling, weakening, or deleting a failing test, lint rule, permission check, tenant filter, database constraint, or migration gate.
- Clearing the dependency cache, flushing every business cache, broadening a transaction, adding `as any`, or disabling an interceptor before evidence identifies that boundary as causal.
- Reimplementing component behavior in a business module, editing a released migration, or claiming PostgreSQL compatibility from H2.
- Logging secrets or full sensitive payloads, or leaving noisy temporary diagnostics in normal execution.
- Calling a bug fixed because code changed, one partial check passed, or another agent reported success.

## Bug Handoff

Report:

- mode: diagnosis, mitigation, or root-cause fix;
- symptom, impact, and exact reproducer or evidence package;
- root-cause chain from original trigger to observed failure;
- hypotheses tested and decisive evidence;
- changed owner/layer and why the fix belongs there;
- regression and verification commands with results and skip counts;
- unverified paths, environmental limits, remaining risk, and follow-up observability;
- mandatory `SQL 执行顺序` when SQL is involved.
