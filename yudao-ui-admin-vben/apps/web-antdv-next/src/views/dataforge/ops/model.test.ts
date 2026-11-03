import { describe, expect, it } from 'vitest';

import { buildCategoryTree, childLogRecord, displayCreated, displayDate, formatDuration, highlightLog, isActiveOpsInstance, layoutFlow, logRows, queueStateLabel, rowIdentity, statusLabel } from './model';

describe('运维实例展示边界', () => {
  it('同编号的源与平台分类分别保留，搜索保留祖先', () => {
    const categories = [
      { id: 'source:9007199254740993', parentId: '0', name: '第三方源' },
      { id: 'source:9007199254740995', parentId: 'source:9007199254740993', name: '实时监测' },
      { id: 'platform:9007199254740993', parentId: '0', name: '平台类目' },
    ];
    const tree = buildCategoryTree(categories, 'DEVELOPMENT', '监测');
    expect(tree[0]?.children?.[0]).toMatchObject({ key: 'source:9007199254740993', children: [{ key: 'source:9007199254740995' }] });
    expect(rowIdentity({ kind: 'DEVELOPMENT', origin: 'SOURCE', id: '9007199254740993' })).not.toBe(rowIdentity({ kind: 'DEVELOPMENT', origin: 'PLATFORM', id: '9007199254740993' }));
  });
  it('无时区时间保持原可见值，运行时长不丢天数或秒', () => {
    expect(displayDate('2026-10-22 07:11:02')).toBe('2026-10-22 07:11');
    expect(displayDate('2026-10-22 19:11:02', true)).toBe('2026-10-22 19:11:02');
    expect(displayCreated('2026-10-22 13:39:14', 'SOURCE', 'Asia/Shanghai')).toBe('2026-10-22 13:39');
    expect(displayCreated('2026-10-22 13:39:14', 'SOURCE', 'America/New_York')).toBe('2026-10-22 01:39');
    expect(displayCreated('2026-10-22 03:00:00', 'SOURCE', 'America/New_York')).toBe('2026-10-21 15:00');
    expect(displayCreated('2026-12-22 13:39:14', 'SOURCE', 'America/New_York')).toBe('2026-12-22 00:39');
    expect(displayCreated('2026-10-22 13:39:14', 'PLATFORM', 'America/New_York')).toBe('2026-10-22 13:39');
    expect(displayCreated('2026-10-22 13:39:14', 'MANAGED', 'America/New_York')).toBe('2026-10-22 13:39');
    expect(displayCreated('2026-10-22 13:39:14', 'WIZARD', 'America/New_York')).toBe('2026-10-22 13:39');
    expect(formatDuration(86_401)).toBe('1d 1s');
    expect(formatDuration(8585)).toBe('2h 23m 5s');
    expect(formatDuration(null)).toBe('-');
  });
  it('作业和整库同步类目保持各自根节点，向导记录不与旧平台记录碰撞', () => {
    expect(buildCategoryTree([], 'JOB')[0]?.title).toBe('作业类目');
    expect(buildCategoryTree([], 'FULL_SYNC')[0]?.title).toBe('整库同步类目');
    expect(rowIdentity({ kind: 'FULL_SYNC', origin: 'WIZARD', id: '9007199254740993' })).not.toBe(rowIdentity({ kind: 'FULL_SYNC', origin: 'PLATFORM', id: '9007199254740993' }));
  });
  it('演示作业子日志使用父作业身份，保留子任务名称', () => {
    const parent = { id: '662', kind: 'JOB' as const, origin: 'SOURCE' as const, taskId: '44', taskName: '每日作业', instanceName: '每日作业实例', executionType: 'START' as const, status: 'SUCCESS' as const };
    const child = { ...parent, id: '42', kind: 'DEVELOPMENT' as const, taskName: '水位统计', instanceName: '水位统计实例' };
    expect(childLogRecord(parent, child)).toMatchObject({ id: '42', kind: 'JOB', origin: 'SOURCE', taskName: '水位统计', instanceName: '水位统计实例', logIdentity: { id: '662', kind: 'JOB', origin: 'SOURCE' } });
  });
  it('排队和停止请求持续观察，丢弃与抢占等队列终态不伪装成运行成功', () => {
    for (const queueState of ['QUEUED', 'DISPATCHING', 'ACTIVE', 'STOP_REQUESTED', 'UNKNOWN'] as const)
      expect(isActiveOpsInstance({ queueState, status: 'UNKNOWN' })).toBe(true);
    for (const queueState of ['DONE', 'DISCARDED', 'SUPERSEDED', 'REJECTED', 'CANCELLED'] as const)
      expect(isActiveOpsInstance({ queueState, status: 'WAITING' })).toBe(false);
    expect(queueStateLabel('QUEUED')).toBe('排队中');
    expect(queueStateLabel('DISCARDED')).toBe('已抛弃');
    expect(queueStateLabel('SUPERSEDED')).toBe('已被抢占');
  });
  it('调度状态未确认时明确显示未知，不转为空闲或成功', () => {
    expect(statusLabel('UNKNOWN')).toBe('未知');
  });
  it('真实集成子实例保留受管身份，不与源快照或旧平台实例混淆', () => {
    const child = { id: '9007199254740993', kind: 'INTEGRATION' as const, origin: 'MANAGED' as const, taskId: '42', taskName: '真实集成', instanceName: '实际DS实例', executionType: 'START' as const, status: 'SUCCESS' as const };
    const parent = { ...child, id: '9007199254740995', kind: 'JOB' as const, origin: 'PLATFORM' as const };
    expect(childLogRecord(parent, child)).toEqual(child);
    expect(rowIdentity(child)).not.toBe(rowIdentity({ ...child, origin: 'SOURCE' }));
    expect(rowIdentity(child)).not.toBe(rowIdentity({ ...child, origin: 'PLATFORM' }));
  });
  it('详情链路按连线水平等距排列，并保留安全节点配置', () => {
    const graph = layoutFlow(JSON.stringify({ nodes: [
      { id: 'a', label: '输入', type: '1', x: 10, y: 300, publicFields: [{ name: 'ID' }] },
      { id: 'b', label: '转换', type: '31', x: 90, y: 20 },
      { id: 'c', label: '输出', type: '91', x: 100, y: 500 },
    ], edges: [{ source: 'a', target: 'b' }, { source: 'b', target: 'c' }] }));
    expect(graph.nodes.map((node) => node.y)).toEqual([0, 0, 0]);
    expect(graph.nodes.map((node) => node.x)).toEqual([0, 164, 328]);
    expect(graph.nodes[0]).toHaveProperty('publicFields', [{ name: 'ID' }]);
  });
  it('多行 JSON 保持一个日志条目，搜索保留原编号并把特殊字符作为普通文本', () => {
    const log = '[2026-08-06 21:01:50.253] INFO 开始\n[2026-08-06 21:01:50.278] INFO {\n  "value": "<img src=x onerror=alert(1)>"\n}\n[2026-08-06 21:01:57.054] INFO 任务成功';
    expect(logRows(log)).toHaveLength(3);
    expect(logRows(log, 'VALUE')[0]).toMatchObject({ line: 2 });
    expect(highlightLog('a.*B.*c', '.*')).toEqual([
      { text: 'a', highlight: false }, { text: '.*', highlight: true },
      { text: 'B', highlight: false }, { text: '.*', highlight: true },
      { text: 'c', highlight: false },
    ]);
    expect(highlightLog('<img>', '<img>')).toEqual([{ text: '<img>', highlight: true }]);
  });
});
