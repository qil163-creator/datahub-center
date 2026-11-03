import type { ModelingDataDomainApi } from '#/api/modeling/datadomain';
import type { ModelingSubjectDomainApi } from '#/api/modeling/subjectdomain';

export const RULE_LABELS = {
  ODS: '贴源逻辑表',
  DIM: '维度逻辑表',
  FACT_PROCESS: '事实-流程',
  FACT_EVENT: '事实-事件',
  DWS: '汇总逻辑表',
  ADS: 'ADS应用表',
} as const;

export function defaultNamingRules(): ModelingDataDomainApi.NamingRule[] {
  return [
    {
      tableType: 'ODS',
      prefix: 'ods_',
      daySuffix: '',
      hourSuffix: '',
      minuteSuffix: '',
      includeSubjectAbbreviation: false,
    },
    {
      tableType: 'FACT_PROCESS',
      prefix: 'dwd_',
      daySuffix: '_da',
      hourSuffix: '',
      minuteSuffix: '',
      includeSubjectAbbreviation: true,
    },
    {
      tableType: 'FACT_EVENT',
      prefix: 'dwd_',
      daySuffix: '_di',
      hourSuffix: '_hi',
      minuteSuffix: '_mi',
      includeSubjectAbbreviation: true,
    },
    {
      tableType: 'DWS',
      prefix: 'dws_',
      daySuffix: '',
      hourSuffix: '_hh',
      minuteSuffix: '_mm',
      includeSubjectAbbreviation: false,
    },
  ];
}

export function namingPreview(
  rule: ModelingDataDomainApi.NamingRule,
  domainCode = '',
  subjectAbbreviation = 'theme',
) {
  if (rule.tableType === 'ODS' || rule.tableType === 'ADS')
    return `${rule.prefix}order`;
  if (rule.tableType === 'DIM')
    return `${rule.prefix}customer${rule.daySuffix}`;
  if (rule.tableType === 'DWS') return `${rule.prefix}order${rule.daySuffix}`;
  return `${rule.prefix}${domainCode.trim() || '{板块英文名}'}${rule.includeSubjectAbbreviation ? `_${subjectAbbreviation.trim() || 'theme'}` : ''}${rule.daySuffix}`;
}

/** 计算主题层级，并防止异常环导致遍历不结束。 */
export function subjectDepth(
  rows: ModelingSubjectDomainApi.SubjectDomain[],
  id?: number | string,
) {
  const byId = new Map(rows.map((row) => [String(row.id), row]));
  const visited = new Set<string>();
  let current = id;
  while (current !== undefined && String(current) !== '0') {
    if (visited.has(String(current))) return Infinity;
    visited.add(String(current));
    current = byId.get(String(current))?.parentId;
  }
  return visited.size;
}

/** 编辑节点排除自己、后代和无法继续新增子级的第五级主题。 */
export function parentOptions(
  rows: ModelingSubjectDomainApi.SubjectDomain[],
  domainId?: number | string,
  editingId?: number | string,
) {
  const excluded = new Set(editingId === undefined ? [] : [String(editingId)]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const row of rows) {
      if (excluded.has(String(row.parentId)) && !excluded.has(String(row.id))) {
        excluded.add(String(row.id));
        changed = true;
      }
    }
  }
  return rows
    .filter(
      (row) =>
        String(row.dataDomainId) === String(domainId) &&
        !excluded.has(String(row.id)) &&
        subjectDepth(rows, row.id) < 5,
    )
    .map((row) => ({
      label: row.name ?? String(row.id),
      value: String(row.id),
    }));
}

/** 下拉挂载到当前弹窗根节点，避免被2000层级的模态遮罩盖住。 */
export function planningPopupContainer(node?: HTMLElement): HTMLElement {
  return (
    node?.closest<HTMLElement>('[data-slot="dialog-content"]') ?? document.body
  );
}
