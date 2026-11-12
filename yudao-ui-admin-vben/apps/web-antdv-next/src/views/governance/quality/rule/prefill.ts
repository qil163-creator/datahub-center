import type { QualityRuleApi } from '#/api/governance/quality/rule';

/**
 * 质量规则表单预填：探查推荐“带入质量规则”时在内存中交接，不放进 URL（参数可能含取值样本）。
 * 只交接一次，取走即清空；页面刷新后自然失效。
 */
let pending: QualityRuleApi.Rule | undefined;

export interface RuleSuggestionLike {
  columnName: string;
  params: Record<string, unknown>;
  qualityDim: string;
  reason: string;
  ruleName: string;
  ruleType: string;
  strategyKey: string;
}

/** 探查推荐 → 质量规则表单值。 */
export function suggestionToRule(
  suggestion: RuleSuggestionLike,
  tableName?: string,
): QualityRuleApi.Rule {
  const source = tableName
    ? `${tableName}.${suggestion.columnName}`
    : suggestion.columnName;
  return {
    ruleName: suggestion.ruleName,
    qualityDim: suggestion.qualityDim,
    ruleType: suggestion.ruleType,
    strategyKey: suggestion.strategyKey || undefined,
    checkLevel: 'FIELD',
    paramSchema: JSON.stringify(suggestion.params ?? {}, null, 2),
    description: `由数据探查推荐（字段 ${source}）：${suggestion.reason}`,
  };
}

export function setRulePrefill(rule: QualityRuleApi.Rule) {
  pending = { ...rule };
}

export function takeRulePrefill(): QualityRuleApi.Rule | undefined {
  const value = pending;
  pending = undefined;
  return value;
}
