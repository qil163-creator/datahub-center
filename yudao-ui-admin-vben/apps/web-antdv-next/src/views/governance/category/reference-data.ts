import type { SafetyCategoryWorkbenchApi as CategoryApi } from '#/api/governance/category/workbench';

import { handleTree } from '@vben/utils';

import { resourceIdKey } from '#/utils/resource-id';

const categoryTimeFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

export function categoryTime(value?: CategoryApi.Time) {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'string') {
    if (/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(value))
      return value.slice(0, 16).replace('T', ' ');
    if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
  }
  let millis: number;
  if (typeof value === 'number') millis = value;
  else if (/^\d+$/.test(value)) millis = Number(value);
  else millis = Date.parse(value);
  if (!Number.isFinite(millis) || Number.isNaN(new Date(millis).getTime()))
    return String(value);
  const parts = Object.fromEntries(
    categoryTimeFormat
      .formatToParts(millis)
      .map((part) => [part.type, part.value]),
  );
  const text = `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
  return /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(text)
    ? text.slice(0, 16)
    : text;
}

export const CATEGORY_FONT =
  "'Helvetica Neue',Helvetica,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',Arial,sans-serif";
export const CATEGORY_STATUS_OPTIONS = [
  { value: 1, label: '禁用' },
  { value: 0, label: '启用' },
];
export const CATEGORY_LEVEL_COLORS: Record<string, string> = {
  L1: '#59af88',
  L2: '#508ff9',
  L3: '#eab038',
  L4: '#db3b34',
};
export const CATEGORY_COLUMNS = [
  {
    title: '编号',
    key: 'id',
    width: 60,
    sorter: true,
    align: 'center' as const,
  },
  {
    title: '分类名称/描述',
    key: 'name',
    minWidth: 220,
    align: 'left' as const,
  },
  { title: '分类编码', key: 'shortName', width: 150, align: 'left' as const },
  {
    title: '所属类目',
    key: 'categoryName',
    width: 150,
    align: 'left' as const,
  },
  { title: '数据分级', key: 'dataLevelId', width: 150, align: 'left' as const },
  { title: '优先级', key: 'priority', width: 150, align: 'left' as const },
  { title: '脱敏配置', key: 'masking', width: 150, align: 'left' as const },
  { title: '状态', key: 'status', minWidth: 120, align: 'center' as const },
  { title: '创建人', key: 'creatorName', width: 120, align: 'center' as const },
  {
    title: '创建时间',
    key: 'createTime',
    width: 150,
    sorter: true,
    align: 'center' as const,
  },
  {
    title: '操作',
    key: 'actions',
    width: 220,
    fixed: 'right' as const,
    align: 'center' as const,
  },
];
export function categoryIcon(name: string) {
  return `/assets/qdata-tag/${name}.svg`;
}
export function categoryDisplayId(row: CategoryApi.Category) {
  return row.sourceRecordId || resourceIdKey(row.id);
}
export function categoryConfigured(row?: CategoryApi.Category) {
  return row?.configured ?? !!resourceIdKey(row?.ruleId);
}
export function validCategoryRevision(value: unknown): value is CategoryApi.Id {
  return typeof value === 'number'
    ? Number.isSafeInteger(value) && value >= 0
    : typeof value === 'string' && /^(?:0|[1-9]\d*)$/u.test(value);
}
export function categoryLevel(
  row: CategoryApi.Category,
  levels: CategoryApi.Level[],
) {
  const option = levels.find(
    (item) => resourceIdKey(item.id) === resourceIdKey(row.dataLevelId),
  );
  return {
    level: row.safetyLevel || row.levelName || option?.safetyLevel,
    color: row.levelColor || option?.color,
  };
}
export function categoryDirectoryName(
  row: CategoryApi.Category,
  directories: CategoryApi.Directory[],
) {
  return (
    row.categoryName ||
    directories.find(
      (item) => resourceIdKey(item.id) === resourceIdKey(row.categoryId),
    )?.categoryName ||
    '-'
  );
}
export function categoryDescendants(
  id: CategoryApi.Id,
  directories: CategoryApi.Directory[],
) {
  const found = new Set([resourceIdKey(id)]);
  for (let changed = true; changed;) {
    changed = false;
    for (const item of directories) {
      const key = resourceIdKey(item.id);
      if (found.has(resourceIdKey(item.parentId)) && !found.has(key)) {
        found.add(key);
        changed = true;
      }
    }
  }
  return [...found];
}
export interface CategoryTreeNode {
  key: string;
  title: string;
  children?: CategoryTreeNode[];
}
export function categoryDirectoryTree(
  directories: CategoryApi.Directory[],
  keyword = '',
): CategoryTreeNode[] {
  const nodes = handleTree(
    directories.map((item) => ({
      id: resourceIdKey(item.id),
      parentId: resourceIdKey(item.parentId) || '0',
      key: resourceIdKey(item.id),
      title: item.categoryName || '-',
    })),
  ) as CategoryTreeNode[];
  const text = keyword.trim().toLocaleLowerCase();
  function filter(items: CategoryTreeNode[]): CategoryTreeNode[] {
    return items.flatMap((node) => {
      if (node.title.toLocaleLowerCase().includes(text)) return [node];
      const children = filter(node.children || []);
      return children.length > 0 ? [{ ...node, children }] : [];
    });
  }
  return text ? filter(nodes) : nodes;
}
