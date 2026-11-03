import type { SafetyWhitelistWorkbenchApi as Api } from '#/api/governance/whitelist/workbench';
import type { SafetyColumn } from '#/views/governance/common/safety-workbench.vue';

import { parseResourceId, resourceIdKey } from '#/utils/resource-id';
import { validCategoryRevision } from '#/views/governance/category/reference-data';
import { ruleCategoryTree } from '#/views/governance/rule/reference-data';

export const WHITELIST_COLUMNS: SafetyColumn[] = [
  { key: 'id', title: '编号', width: 60, align: 'center', sorter: true },
  { key: 'name', title: '白名单名称/描述', width: 260, align: 'left' },
  { key: 'categoryName', title: '数据分类', width: 180, align: 'left' },
  { key: 'effectiveCategory', title: '生效分类', width: 120, align: 'left' },
  { key: 'users', title: '生效账号', width: 180, align: 'left' },
  { key: 'period', title: '生效时段', width: 190, align: 'left' },
  { key: 'status', title: '状态', width: 120, align: 'center' },
  { key: 'creatorName', title: '创建人', minWidth: 120, align: 'center' },
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
export function whitelistDisplayId(row: Api.Row) {
  return row.sourceRecordId || resourceIdKey(row.id);
}
export function whitelistVersion(row?: Api.Row): Api.Version | undefined {
  return row && parseResourceId(row.id) && validCategoryRevision(row.revision)
    ? { id: row.id, revision: row.revision }
    : undefined;
}
export function whitelistCategoryTree(options: Api.Options, selectable = true) {
  return ruleCategoryTree({
    directories: options.directories,
    categories: options.categories.map((item) => ({
      ...item,
      configured: selectable && item.whitelistConfigured,
    })),
  });
}
export function whitelistUserNames(users: Api.User[]) {
  return (
    users
      .map((item) => item.nickname)
      .filter(Boolean)
      .join('、') || '-'
  );
}
export function whitelistPeriod(row: Pick<Api.Row, 'endTime' | 'startTime'>) {
  return row.startTime && row.endTime ? `${row.startTime}~${row.endTime}` : '-';
}
export function newWhitelist(): Api.Save {
  return {
    name: '',
    categoryId: '',
    effectiveCategory: '1',
    userIds: [],
    startTime: '',
    endTime: '',
    status: 1,
    description: '',
  };
}
function validDate(date: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return false;
  const value = new Date(`${date}T00:00:00Z`);
  return (
    !Number.isNaN(value.getTime()) && value.toISOString().slice(0, 10) === date
  );
}
export function whitelistError(form: Api.Save) {
  if (
    !/^[\u4E00-\u9FA5_\da-z]+$/i.test(form.name.trim()) ||
    form.name.trim().length > 50
  )
    return '白名单名称只支持中英文、数字、下划线，长度为1到50个字符。';
  if (!parseResourceId(form.categoryId)) return '请选择数据分类。';
  if (form.effectiveCategory !== '1') return '当前仅支持选择用户。';
  if (
    form.userIds.length === 0 ||
    form.userIds.length > 100 ||
    form.userIds.some((id) => !parseResourceId(id))
  )
    return '请选择1到100个有效本地账号。';
  if (
    !validDate(form.startTime) ||
    !validDate(form.endTime) ||
    form.endTime < form.startTime
  )
    return '请选择有效的开始日期和结束日期。';
  if (form.description && form.description.length > 256)
    return '描述不能超过256个字符。';
}
