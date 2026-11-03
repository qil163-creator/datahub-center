import type { SafetyRuleWorkbenchApi as Api } from '#/api/governance/rule/workbench';
import type { SafetyColumn } from '#/views/governance/common/safety-workbench.vue';

import { handleTree } from '@vben/utils';

import { parseResourceId, resourceIdKey } from '#/utils/resource-id';
import { validCategoryRevision } from '#/views/governance/category/reference-data';

export const RULE_COLUMNS: SafetyColumn[] = [
  { key: 'id', title: '编号', width: 60, align: 'center', sorter: true },
  { key: 'ruleName', title: '脱敏规则名称/描述', minWidth: 240, align: 'left' },
  { key: 'categoryName', title: '数据分类', minWidth: 180, align: 'left' },
  { key: 'applicationScene', title: '应用场景', width: 120, align: 'center' },
  { key: 'maskType', title: '脱敏方式', width: 120, align: 'center' },
  { key: 'status', title: '状态', minWidth: 120, align: 'center' },
  { key: 'creatorName', title: '创建人', width: 120, align: 'center' },
  {
    key: 'createTime',
    title: '创建时间',
    width: 150,
    align: 'center',
    sorter: true,
  },
  {
    key: 'actions',
    title: '操作',
    width: 220,
    align: 'center',
    fixed: 'right',
  },
];
export const SCENE_LABELS: Record<string, string> = {
  '1': '数据资产',
  '2': '数据查询',
  '3': '数据服务',
};
export function ruleDisplayId(row: Api.Rule) {
  return row.sourceRecordId || resourceIdKey(row.id);
}
export function ruleVersion(row?: Api.Rule): Api.Version | undefined {
  return row &&
    parseResourceId(row.id) &&
    validCategoryRevision(row.categoryRevision) &&
    parseResourceId(row.ruleVersionId) &&
    row.ruleRevisionToken
    ? {
        id: row.id,
        categoryRevision: row.categoryRevision,
        ruleVersionId: row.ruleVersionId,
        ruleRevisionToken: row.ruleRevisionToken,
      }
    : undefined;
}
interface CategoryNode {
  key: string;
  value: string;
  title: string;
  disabled: boolean;
  children?: CategoryNode[];
}
export function ruleCategoryTree(
  options: Api.Options,
  currentCategoryId?: Api.Id,
): CategoryNode[] {
  const nodes = handleTree(
    options.directories.map((item) => ({
      id: `directory:${resourceIdKey(item.id)}`,
      parentId:
        resourceIdKey(item.parentId) === '0'
          ? '0'
          : `directory:${resourceIdKey(item.parentId)}`,
      key: `directory:${resourceIdKey(item.id)}`,
      value: `directory:${resourceIdKey(item.id)}`,
      title: item.categoryName || '-',
      disabled: true,
    })),
  ) as CategoryNode[];
  const categories = options.categories
    .filter(
      (item) =>
        !item.configured ||
        resourceIdKey(item.id) === resourceIdKey(currentCategoryId),
    )
    .map((item) => ({
      key: resourceIdKey(item.id),
      value: resourceIdKey(item.id),
      title: item.name || '-',
      disabled: false,
      directoryId: resourceIdKey(item.categoryId),
    }));
  const used = new Set<string>();
  function attach(items: CategoryNode[]) {
    for (const node of items) {
      const children = categories.filter(
        (item) => `directory:${item.directoryId}` === node.key,
      );
      for (const child of children) used.add(child.key);
      node.children = [...(node.children || []), ...children];
      attach(node.children.filter((item) => item.key.startsWith('directory:')));
    }
  }
  attach(nodes);
  return [...nodes, ...categories.filter((item) => !used.has(item.key))];
}
export function safetySort(value: unknown): {
  sortField: 'createTime' | 'id';
  sortOrder: 'asc' | 'desc';
} {
  const sorter = (Array.isArray(value) ? value[0] : value) as
    | undefined
    | { columnKey?: string; order?: string };
  return {
    sortField: sorter?.columnKey === 'id' ? 'id' : 'createTime',
    sortOrder: sorter?.order === 'ascend' ? 'asc' : 'desc',
  };
}
