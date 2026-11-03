import { describe, expect, it } from 'vitest';

import { standardCategoryTree, standardTreeKeys } from './data';

describe('标准类目树', () => {
  it('保留大整数ID并在搜索叶节点时保留祖先路径', () => {
    const rows = [
      { id: '9007199254740993', categoryName: '基础主题域' },
      {
        id: '9007199254740994',
        parentId: '9007199254740993',
        categoryName: '河道',
      },
      { id: 2, categoryName: '统计主题域' },
    ];
    const tree = standardCategoryTree(rows, '河道');
    expect(tree[0]?.children).toHaveLength(1);
    expect(tree[0]?.children?.[0]?.id).toBe('9007199254740993');
    expect(tree[0]?.children?.[0]?.children?.[0]?.id).toBe('9007199254740994');
    expect(standardTreeKeys(tree)).toEqual(['all', '9007199254740993']);
  });
  it('循环与缺失父节点仍可展示且不会无限递归', () => {
    const tree = standardCategoryTree([
      { id: 1, parentId: 2, categoryName: 'A' },
      { id: 2, parentId: 1, categoryName: 'B' },
      { id: 3, parentId: 8, categoryName: 'C' },
    ]);
    expect(tree[0]?.children?.map((row) => row.key)).toEqual(['1', '2', '3']);
  });
});
