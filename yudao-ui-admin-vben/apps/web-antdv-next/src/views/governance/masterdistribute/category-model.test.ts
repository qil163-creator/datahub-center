import { describe, expect, it } from 'vitest';

import { buildCategoryOptions, buildCategoryTree } from './category-model';

const rows = [
  { id: '9007199254740993', parentId: 0, name: '业务类目', sort: 1, status: 0 },
  { id: '9007199254740994', parentId: '9007199254740993', name: '财务推送', sort: 1, status: 0 },
  { id: '9007199254740995', parentId: '9007199254740993', name: '停用类目', sort: 2, status: 1 },
];

describe('主数据推送类目树', () => {
  it('搜索子类目保留祖先并无损保留编号', () => {
    expect(buildCategoryTree(rows, '财务')).toEqual([expect.objectContaining({
      key: '9007199254740993',
      children: [expect.objectContaining({ key: '9007199254740994', title: '财务推送' })],
    })]);
  });
  it('停用类目不可新选，其他类目可选', () => {
    const parent = buildCategoryOptions(rows)[0]!;
    expect(parent.value).toBe('9007199254740993');
    expect(parent.children[0]!.disabled).toBe(false);
    expect(parent.children[1]!.disabled).toBe(true);
  });
  it('停用父类目不影响启用子类目的选择', () => {
    const parent = buildCategoryOptions([
      { id: 1, parentId: 0, name: '停用父类目', sort: 1, status: 1 },
      { id: 2, parentId: 1, name: '启用子类目', sort: 1, status: 0 },
    ])[0]!;
    expect(parent.disabled).toBe(true);
    expect(parent.children[0]!.disabled).toBe(false);
  });
});
