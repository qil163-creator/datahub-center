import type { ProjectCategoryApi as Api } from '#/api/dataforge/projectcategory';

import { describe, expect, it } from 'vitest';

import {
  branchKeys,
  categoryParentOptions,
  categoryPermission,
  categoryTree,
  filterCategoryTree,
  isPositiveCount,
} from './model';

const parent = '9007199254740993';
const child = '9007199254740995';
const grandchild = '9007199254740997';
const rows: Api.Category[] = [
  {
    id: grandchild,
    parentId: child,
    name: '雨量明细',
    status: 0,
    sort: 2,
    taskCount: 0,
    childCount: 0,
  },
  {
    id: 2,
    parentId: 0,
    name: '应用服务',
    status: 0,
    sort: 3,
    taskCount: 0,
    childCount: 0,
  },
  {
    id: parent,
    parentId: 0,
    name: '数据同步',
    status: 0,
    sort: 0,
    taskCount: 0,
    childCount: 1,
  },
  {
    id: child,
    parentId: parent,
    name: '原始数据',
    status: 0,
    sort: 0,
    taskCount: 0,
    childCount: 1,
  },
];

describe('项目类目树与筛选', () => {
  it('无损构造父子关系并按排序显示，不修改接口对象', () => {
    const tree = categoryTree(rows);
    expect(tree.map((row) => row.id)).toEqual([parent, 2]);
    expect(tree[0]?.children?.[0]?.children?.[0]?.id).toBe(grandchild);
    expect(branchKeys(tree)).toEqual([parent, child]);
    expect(rows.every((row) => !('children' in row))).toBe(true);
  });

  it('名称与父级组合查询保留匹配叶子的完整祖先', () => {
    const tree = filterCategoryTree(categoryTree(rows), '  雨量  ', child);
    expect(tree).toHaveLength(1);
    expect(tree[0]?.id).toBe(parent);
    expect(tree[0]?.children?.[0]?.id).toBe(child);
    expect(tree[0]?.children?.[0]?.children?.[0]?.id).toBe(grandchild);
    expect(filterCategoryTree(categoryTree(rows), '不存在')).toEqual([]);
  });

  it('编辑时排除自身及全部后代，其他类目和顶级节点仍可选择', () => {
    expect(categoryParentOptions(rows, parent)).toEqual([
      {
        title: '顶级节点',
        value: '0',
        children: [{ title: '应用服务', value: '2', children: undefined }],
      },
    ]);
    const nested = categoryParentOptions(rows, child);
    expect(nested[0]?.children?.map((item) => item.value)).toEqual([
      parent,
      '2',
    ]);
    expect(nested[0]?.children?.[0]?.children).toBeUndefined();
  });

  it('页面入口使用后端种类对应的精确权限码', () => {
    expect(categoryPermission('development', 'query')).toBe(
      'dataforge:dev-category:query',
    );
    expect(categoryPermission('integration', 'delete')).toBe(
      'dataforge:integration-category:delete',
    );
    expect(categoryPermission('masterPush', 'delete')).toBe(
      'governance:master-push-category:delete',
    );
  });

  it('关联计数同时接受数字和Long字符串，零不误判为有关联', () => {
    expect(isPositiveCount(0)).toBe(false);
    expect(isPositiveCount('0')).toBe(false);
    expect(isPositiveCount(null)).toBe(false);
    expect(isPositiveCount('9007199254740993')).toBe(true);
    expect(isPositiveCount(2)).toBe(true);
  });
});
