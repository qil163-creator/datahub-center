import { describe, expect, it } from 'vitest';

import { buildWorkItemTree } from './work-item-tree';

describe('组织实例树', () => {
  it('按上级编号组织成树，上级缺失的作为根，叶子不带空 children', () => {
    const tree = buildWorkItemTree([
      { id: 1, orgName: '市局' },
      { id: '9007199254740993', orgName: '区局', parentWorkItemId: 1 },
      { id: 3, orgName: '科室', parentWorkItemId: '9007199254740993' },
      { id: 4, orgName: '孤立', parentWorkItemId: 99 },
    ]);
    expect(tree.map((node) => node.orgName)).toEqual(['市局', '孤立']);
    expect(tree[0]?.children?.[0]?.orgName).toBe('区局');
    expect(tree[0]?.children?.[0]?.children?.[0]?.orgName).toBe('科室');
    expect(tree[1]?.children).toBeUndefined();
  });
});
