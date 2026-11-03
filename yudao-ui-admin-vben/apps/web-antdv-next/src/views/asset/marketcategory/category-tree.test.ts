import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';

import { describe, expect, it } from 'vitest';

import {
  marketCategoryParentOptions,
  marketCategoryTree,
} from './category-tree';

const category = (
  id: number | string,
  parentId: number | string = 0,
): AssetMarketCategoryApi.MarketCategory => ({
  id,
  parentId,
  name: `类目${id}`,
  status: 0,
  sort: 0,
});

describe('集市类目树', () => {
  it('保留超长 ID、混合数字父 ID 和缺少父节点的筛选结果，不改变接口原始记录', () => {
    const rows = [
      category('9007199254740993'),
      category(2, '9007199254740993'),
      category('3', '2'),
      category(4, 999),
    ];
    const tree = marketCategoryTree(rows);
    expect(tree.map((row) => row.id)).toEqual(['9007199254740993', 4]);
    expect(tree[0]?.children?.[0]?.children?.[0]?.id).toBe('3');
    expect(rows.every((row) => row.children === undefined)).toBe(true);
  });

  it('父节点选项排除自身和所有下级，新增时允许顶层或任意现有父类目', () => {
    const rows = [
      category('9007199254740993'),
      category(2, '9007199254740993'),
      category(3, 2),
      category(4),
    ];
    const edit = marketCategoryParentOptions(rows, '9007199254740993');
    expect(edit[0]?.id).toBe(0);
    expect(edit[0]?.children.map((row) => row.id)).toEqual([4]);
    expect(marketCategoryParentOptions(rows)[0]?.children).toHaveLength(2);
  });
});
