import type { DataforgeAccessApi } from '#/api/dataforge/access';

/** 确认只对应当前物理表、列和完整映射诊断，不扩大成整表接受。 */
export function matchesMappingDecision(
  decision: DataforgeAccessApi.TypeMappingAcknowledgement,
  plan: DataforgeAccessApi.TypeMappingPlan,
  column: DataforgeAccessApi.TypeMappingColumn,
) {
  return (
    !plan.truncated &&
    column.fidelity === 'LOSSY' &&
    decision.targetProduct === plan.targetProduct &&
    decision.targetSchema === plan.targetSchema &&
    decision.targetTable === plan.targetTable &&
    decision.column === column.column &&
    decision.mappingFingerprint === column.mappingFingerprint &&
    decision.lossCodes.length === column.lossCodes.length &&
    new Set(decision.lossCodes).size === decision.lossCodes.length &&
    decision.lossCodes.every((code) => column.lossCodes.includes(code))
  );
}

export function sameMappingTable(
  decision: DataforgeAccessApi.TypeMappingAcknowledgement,
  plan: DataforgeAccessApi.TypeMappingPlan,
) {
  return (
    decision.targetProduct === plan.targetProduct &&
    decision.targetSchema === plan.targetSchema &&
    decision.targetTable === plan.targetTable
  );
}

export function isTypeMappingPlan(
  value: unknown,
): value is DataforgeAccessApi.TypeMappingPlan {
  if (!value || typeof value !== 'object') return false;
  const plan = value as DataforgeAccessApi.TypeMappingPlan;
  const fidelities = new Set(['EXACT', 'LOSSY', 'UNSUPPORTED']);
  if (
    plan.schemaVersion !== 1 ||
    typeof plan.accepted !== 'boolean' ||
    !fidelities.has(plan.fidelity)
  )
    return false;
  if (
    typeof plan.truncated !== 'boolean' ||
    !Number.isSafeInteger(plan.totalColumns) ||
    plan.totalColumns < 1
  )
    return false;
  if (
    ![
      plan.sourceProduct,
      plan.sourceSchema,
      plan.sourceTable,
      plan.targetProduct,
      plan.targetSchema,
      plan.targetTable,
    ].every((name) => typeof name === 'string' && name.trim().length > 0)
  )
    return false;
  if (!Array.isArray(plan.columns) || plan.columns.length === 0) return false;
  if (
    plan.columns.length > plan.totalColumns ||
    (!plan.truncated && plan.columns.length !== plan.totalColumns)
  )
    return false;
  const names = new Set<string>();
  for (const column of plan.columns) {
    if (
      !column ||
      typeof column.column !== 'string' ||
      !column.column.trim() ||
      names.has(column.column)
    )
      return false;
    names.add(column.column);
    if (
      !fidelities.has(column.fidelity) ||
      typeof column.accepted !== 'boolean' ||
      typeof column.mappingFingerprint !== 'string' ||
      !/^[a-f\d]{64}$/u.test(column.mappingFingerprint)
    )
      return false;
    if (
      !Array.isArray(column.lossCodes) ||
      !column.lossCodes.every(
        (code) =>
          typeof code === 'string' && /^[A-Z][A-Z\d_]{0,95}$/u.test(code),
      ) ||
      new Set(column.lossCodes).size !== column.lossCodes.length ||
      (column.fidelity === 'LOSSY' && column.lossCodes.length === 0)
    )
      return false;
    if (
      !Array.isArray(column.issues) ||
      !column.issues.every(
        (issue) =>
          issue &&
          typeof issue.severity === 'string' &&
          typeof issue.code === 'string' &&
          typeof issue.detail === 'string',
      )
    )
      return false;
  }
  return true;
}
