import type { IntegrationApi as Api } from '#/api/dataforge/integration';

import { describe, expect, it } from 'vitest';

import {
  categoryTree,
  cronSummary,
  defaultTask,
  readFlow,
  recentRunTime,
  saveTask,
} from './model';

describe('集成任务跨端边界', () => {
  it('最近执行时间同日显示时分，跨日和较早记录保持相对时间', () => {
    const now = new Date(2026, 9, 22, 12, 0).getTime();
    expect(recentRunTime(new Date(2026, 9, 22, 9, 2).getTime(), now)).toBe(
      '09:02',
    );
    expect(recentRunTime(new Date(2026, 9, 21, 23, 59).getTime(), now)).toBe(
      '1天前',
    );
    expect(recentRunTime(new Date(2026, 7, 4, 9, 2).getTime(), now)).toBe(
      '2月前',
    );
    expect(recentRunTime(new Date(2025, 7, 4, 9, 2).getTime(), now)).toBe(
      '1年前',
    );
    expect(recentRunTime(null, now)).toBe('-');
    expect(cronSummary('0 0 0 1 * ?')).toBe('在00:00, 限每月 1 号 执行');
  });

  it('编辑源快照时保留无损编号，并要求重新指定平台负责人', () => {
    const source = {
      ...defaultTask('OFFLINE'),
      id: '9007199254740993',
      revision: 2,
      ownerId: null,
      ownerName: '源负责人',
      contactNumber: '不提交的源联系方式',
      scheduleStatus: 0,
      configurationStatus: 'COMPLETE',
      executable: false,
      importedSnapshot: true,
      lastRunStatus: 'SUCCESS',
      flowJson: '{"nodes":[]}',
    } as Api.Task;
    const request = saveTask(source);
    expect(request.id).toBe('9007199254740993');
    expect(request.ownerId).toBe('');
    expect(request).not.toHaveProperty('flowJson');
    expect(request).not.toHaveProperty('contactNumber');
    expect(request).not.toHaveProperty('scheduleStatus');
    request.engineConfig.driverMemory = '1G';
    expect(source.engineConfig.driverMemory).toBe('512m');
  });

  it('分类树保留大整数ID，禁用类目仍可浏览但不能用于任务选择', () => {
    const categories = [
      { id: '9007199254740993', parentId: '0', name: '上级' },
      {
        id: '9007199254740995',
        parentId: '9007199254740993',
        name: '停用类目',
        validFlag: false,
      },
    ];
    expect(categoryTree(categories)[0]?.children?.[0]).toMatchObject({
      key: '9007199254740995',
      disabled: false,
    });
    expect(categoryTree(categories, true)[0]?.children?.[0]).toMatchObject({
      key: '9007199254740995',
      disabled: true,
    });
  });

  it('流程只读图居中归一化坐标，保留源节点类型且剔除悬空边', () => {
    const graph = readFlow(
      JSON.stringify({
        nodes: [
          { id: 'input', label: '表输入组件', type: '1', x: 220, y: 170 },
          { id: 'map', label: '值映射', type: '47', x: 320, y: 240 },
        ],
        edges: [
          { source: 'input', target: 'map' },
          { source: 'missing', target: 'map' },
        ],
      }),
    );
    expect(graph.nodes).toEqual([
      { id: 'input', label: '表输入组件', type: '1', x: 0, y: 0 },
      { id: 'map', label: '值映射', type: '47', x: 100, y: 70 },
    ]);
    expect(graph.edges).toEqual([{ source: 'input', target: 'map' }]);
    expect(readFlow('{')).toEqual({ nodes: [], edges: [] });
    expect(readFlow('{"nodes":{}}')).toEqual({ nodes: [], edges: [] });
  });
});
