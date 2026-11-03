# Skill Evaluation

Read this reference only when maintaining, reviewing, or behaviorally evaluating this Skill. Validation should establish that another Codex instance makes better repository decisions, not merely that files contain expected headings or phrases.

## Evaluation Setup

- Run `skill-creator/scripts/quick_validate.py` for packaging/frontmatter first, but do not treat it as behavioral proof.
- Use an independent agent when available. Give it the Skill path, one realistic user request, and only the repository/worktree evidence the request would normally expose. Do not reveal the intended answer, suspected failure, or this rubric before it acts.
- Use a disposable worktree or temporary fixture for scenarios that write files. Never point evaluation prompts at production databases, live credentials, external customer systems, or mutable shared infrastructure.
- Preserve a clean comparison: capture the starting Git status and inspect actual commands, changed files, tests, and final handoff. A plausible narrative without corresponding artifacts or evidence fails.
- Test progressive disclosure. The evaluator should read the closest `AGENTS.md`, `SKILL.md`, and only references relevant to the scenario; loading every reference for a narrow task is a routing defect.

## Representative Scenario Set

Select a small set that covers the behavior changed by the Skill revision:

| Scenario | Observable decisions |
| --- | --- |
| Backend CRUD plus a new column/menu | Uses the owning module and mature patterns, checks the global error-code policy, creates the next repository-gated migration, updates the fresh baseline, and reports exact SQL order. |
| New DB Switch product or operation | Produces a product-by-operation evidence matrix, distinguishes proven/partial/unknown cells, extends the component only when ownership fits, and tests the real affected dialect. |
| Cross-service durable write | Chooses RPC or async from outcome semantics, handles timeout/unknown result, idempotency and reconciliation, and does not invent an existing outbox. |
| Internal RPC or public API | Separates trust zones, rejects spoofable identity headers, preserves provider authorization, and defines signing/replay/idempotency independently for an open API. |
| Frontend identity-dependent dictionary/form | Preserves mixed `Long` wire types, default/annotated dates and exact decimals; stale requests cannot overwrite a newer selection or authenticated user. |
| 业务列表或树表新增/调整操作列 | 使用原生右侧冻结，按实际按钮及“更多”入口设置足够宽度并关闭操作单元格省略；保留权限与操作，在实际横向滚动的窄容器中验证固定位置、按钮完整性及菜单浮层，而非仅匹配配置文字。 |
| Presigned upload or large export | Models transfer and metadata finalization separately, enforces backend scope, and covers retry/orphan/expiry and download boundaries. |
| XXL-Job handler | 在单平台范围执行一次，无租户参数或上下文依赖；证明执行人/对象范围、分批处理和重试幂等。 |
| Java/Spring/Jackson framework change | Resolves effective Java 25/Boot 4 configuration, checks Jackson 2/3 types and dependencies, starts the affected context, and verifies raw JSON contracts rather than reporting compile-only success. |
| Intermittent cross-boundary bug | Preserves the original evidence, localizes the first divergent boundary, tests one explicit hypothesis at a time, fixes the owning layer or shared component, and proves the original symptom without arbitrary sleeps or suppressed errors. |
| Local deterministic regression | Uses the debugging fast path: exact failure, targeted symbol/caller search, one working neighbor, one discriminating test, minimal owner-layer fix, focused fresh verification, and then stops without loading unrelated references or running broad suites. |

Do not require every scenario on each revision. Choose cases that can disprove the changed guidance, plus one narrow ordinary task to ensure the Skill has not become over-broad.

## Scoring Rubric

Evaluate observable behavior in these dimensions:

1. **Routing and efficiency:** finds both repository roots when needed, reads nearest applicable instructions, distinguishes current source/tests from stale documentation, loads only relevant references, and chooses narrow high-information checks before broader inspection.
2. **Ownership and scope:** preserves user changes, keeps domain policy in the owning module, reuses or extends components deliberately, and does not create unrelated counterpart work.
3. **Contracts and security:** maintains module/API direction, lossless wire types, department/object data scope, trust boundaries, external-resource limits, and explicit distributed failure semantics.
4. **Database discipline:** uses `tools/quality/check_migrations.py --next`, fixed-width global ordering, legacy prerequisites, baseline marker, immutable history, real dependencies, and truthful execution instructions.
5. **Debugging discipline:** distinguishes diagnosis from implementation, uses the fast path only when its criteria hold, captures a reproducer or evidence package before editing, traces to the owning root cause, uses discriminating experiments, escalates on risk or uncertainty, and stops after sufficient fresh proof instead of stacking patches or continuing unrelated investigation.
6. **Verification:** runs risk-matched checks, exercises the original symptom, inspects skips and runtime behavior, uses real PostgreSQL where semantics require it, and never presents an unrun or skipped check as passing.
7. **Handoff quality:** reports the smallest coherent change, root-cause evidence, actual verification, remaining risks, and exact SQL sequence when applicable.

A critical safety or authorization failure, skipping root-cause or verification evidence in the name of speed, speculative production mutation, symptom-only patch presented as a root-cause fix, modification of an immutable migration, lossy identifier/decimal conversion, direct cross-module table write, fabricated component capability, or fabricated test result is a failing outcome even if other dimensions look good.

## Review and Iterate

- Compare decisions and artifacts, not wording. Avoid tests that only grep for mandated terms or force a fixed implementation when several safe designs are valid.
- Trace each observed failure to the narrowest instruction or missing repository tool. Prefer updating a focused reference over expanding `SKILL.md`; remove duplicated or speculative guidance.
- Re-run the failing scenario and one adjacent regression scenario after a change. Do not universalize one application's workaround without current repository evidence.
- Record evaluator model/settings, prompt, starting revision, commands, outcome, and any environmental limitation so later regressions are comparable.
