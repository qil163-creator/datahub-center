import { describe, expect, it } from 'vitest';

import { taskPalette } from './task-palette';

describe('作业任务类目树', () => {
  const categories = [
    { id: '1', name: '河道', kind: 'INTEGRATION' as const },
    { id: '2', parentId: '1', name: '每日', kind: 'INTEGRATION' as const },
    { id: '3', name: '每日', kind: 'INTEGRATION' as const },
  ];
  const tasks = [{ id: '9007199254740993', name: '流量', categoryId: '2', kind: 'INTEGRATION' as const, executable: true }, { id: '2', name: '雨量', categoryId: '3', kind: 'INTEGRATION' as const, executable: false }];
  it('保留嵌套类目、同名类目和无损任务编号', () => {
    const group = taskPalette(tasks, categories, '', [])[0]!;
    expect(group.rows.map((row) => row.key)).toEqual(['INTEGRATION/1', 'INTEGRATION/2', 'INTEGRATION:task:9007199254740993', 'INTEGRATION/3', 'INTEGRATION:task:2']);
    expect(group.rows[2]?.depth).toBe(3);
  });
  it('检索祖先类目时展开命中路径，折叠时不泄漏子行', () => {
    expect(taskPalette(tasks, categories, '河道', ['INTEGRATION/1'])[0]?.rows).toHaveLength(3);
    expect(taskPalette(tasks, categories, '', ['INTEGRATION/1'])[0]?.rows.map((row) => row.key)).toEqual(['INTEGRATION/1', 'INTEGRATION/3', 'INTEGRATION:task:2']);
  });
  it('根目录不挂到 categoryId=0 的未分类任务下', () => {
    const group = taskPalette([
      { id: '1', name: '已分类任务', categoryId: '10', kind: 'DEVELOPMENT', executable: true },
      { id: '2', name: '未分类任务', categoryId: 0, kind: 'DEVELOPMENT', executable: false },
    ], [{ id: '10', parentId: 0, name: '目录', kind: 'DEVELOPMENT' }], '', [])[1]!;
    expect(group.rows.filter((row) => row.type === 'category')).toEqual([
      { type: 'category', key: 'DEVELOPMENT/10', name: '目录', depth: 1, count: 1 },
      { type: 'category', key: 'DEVELOPMENT/0', name: '未分类', depth: 1, count: 1 },
    ]);
  });
});
