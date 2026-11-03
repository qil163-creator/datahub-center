import type { GovernanceCleanRuleApi } from '#/api/governance/cleanrule';

interface GovernanceCleanSelection {
  ruleId: string;
  field?: string;
  fields?: string[];
  config?: unknown;
}

export function selectGovernanceCleanRules(
  rules: GovernanceCleanRuleApi.CleanRule[],
) {
  return rules.filter(
    (rule) =>
      (rule.ruleLevel === 1 || rule.ruleLevel === 2) &&
      rule.id !== null &&
      rule.id !== undefined &&
      rule.status === 0 &&
      rule.engineSupported === true &&
      typeof rule.rowTransformSupported === 'boolean',
  );
}

export function createGovernanceCleanSelection(
  rule: GovernanceCleanRuleApi.CleanRule,
  firstInputField = '',
) {
  if (governanceCleanUsesAllFields(rule)) return { ruleId: String(rule.id) };
  return governanceCleanUsesMultipleFields(rule)
    ? {
        ruleId: String(rule.id),
        fields: firstInputField ? [firstInputField] : [],
      }
    : { ruleId: String(rule.id), field: firstInputField };
}

export function governanceCleanUsesMultipleFields(
  rule?: GovernanceCleanRuleApi.CleanRule,
) {
  return rule?.columnBinding === 'MULTIPLE';
}

export function governanceCleanUsesAllFields(
  rule?: GovernanceCleanRuleApi.CleanRule,
) {
  return rule?.columnBinding === 'ALL';
}

export function isGovernanceCleanSelectionEditable(
  selection: unknown,
): selection is GovernanceCleanSelection {
  if (!selection || typeof selection !== 'object') return false;
  const candidate = selection as Record<string, unknown>;
  if (typeof candidate.ruleId !== 'string') return false;
  if (
    candidate.field === undefined &&
    candidate.fields === undefined &&
    candidate.config === undefined
  )
    return true;
  if (typeof candidate.field === 'string') return true;
  if (
    Array.isArray(candidate.fields) &&
    candidate.fields.every((field) => typeof field === 'string')
  )
    return true;
  if (!candidate.config || typeof candidate.config !== 'object') return false;
  const config = candidate.config as Record<string, unknown>;
  return (
    Array.isArray(config.columns) &&
    config.columns.length > 0 &&
    config.columns.every((field) => typeof field === 'string')
  );
}

export function governanceCleanSelectionFields(selection: unknown): string[] {
  if (!selection || typeof selection !== 'object') return [];
  const candidate = selection as Record<string, unknown>;
  const config = candidate.config as Record<string, unknown> | undefined;
  const fields = candidate.fields ?? config?.columns;
  return Array.isArray(fields) &&
    fields.every((field) => typeof field === 'string')
    ? fields
    : [];
}

export function setGovernanceCleanSelectionFields(
  selection: unknown,
  fields: string[],
) {
  if (!selection || typeof selection !== 'object') return;
  const candidate = selection as Record<string, unknown>;
  if (candidate.config && typeof candidate.config === 'object')
    (candidate.config as Record<string, unknown>).columns = fields;
  else {
    delete candidate.field;
    candidate.fields = fields;
  }
}

export function governanceCleanSelectionField(selection: unknown): string {
  if (!selection || typeof selection !== 'object') return '';
  const candidate = selection as Record<string, unknown>;
  if (typeof candidate.field === 'string') return candidate.field;
  if (!candidate.config || typeof candidate.config !== 'object') return '';
  const columns = (candidate.config as Record<string, unknown>).columns;
  return Array.isArray(columns) && typeof columns[0] === 'string'
    ? columns[0]
    : '';
}

export function setGovernanceCleanSelectionField(
  selection: unknown,
  field: string,
) {
  if (!selection || typeof selection !== 'object') return;
  const candidate = selection as Record<string, unknown>;
  if (typeof candidate.field === 'string') {
    candidate.field = field;
    return;
  }
  if (!candidate.config || typeof candidate.config !== 'object') return;
  const config = candidate.config as Record<string, unknown>;
  if (Array.isArray(config.columns) && config.columns.length === 1)
    config.columns[0] = field;
}

export function governanceRuleParameters(config: unknown): string {
  if (config === null || config === undefined) return '未配置持久化参数';
  if (!config || typeof config !== 'object' || Array.isArray(config))
    return '{}';
  const parameters = { ...(config as Record<string, unknown>) };
  delete parameters.columns;
  return JSON.stringify(parameters);
}
