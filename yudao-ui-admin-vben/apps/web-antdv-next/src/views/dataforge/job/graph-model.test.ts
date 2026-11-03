import { describe, expect, it } from 'vitest';

import { canConnectJobNodes, parseJobFlow } from './graph-model';

describe('作业流程契约', () => {
  it('保留大整数任务编号和已有元数据', () => {
    const taskId = '9223372036854775806';
    const result = parseJobFlow(
      JSON.stringify({
        nodes: [
          {
            id: 'task-a',
            kind: 'DEVELOPMENT',
            taskId,
            name: '月水位统计',
            x: 18,
            y: 25,
            description: '已有说明',
          },
        ],
        edges: [],
        version: 2,
      }),
    );
    expect(result.nodes[0]?.taskId).toBe(taskId);
    expect(result.nodes[0]?.description).toBe('已有说明');
    expect(result.version).toBe(2);
  });

  it('损坏的图不能静默成为空流程', () => {
    expect(() => parseJobFlow('{')).toThrow(SyntaxError);
    expect(() => parseJobFlow('{"nodes":[]}')).toThrow('缺少节点或连线');
    expect(() =>
      parseJobFlow(
        '{"nodes":[],"edges":[{"source":"missing","target":"other"}]}',
      ),
    ).toThrow('不存在的节点');
  });

  it('阻止自环、重复依赖和间接循环，同时允许分支汇合', () => {
    const edges = [
      { source: 'a', target: 'b' },
      { source: 'b', target: 'c' },
    ];
    expect(canConnectJobNodes(edges, 'a', 'a')).toBe(false);
    expect(canConnectJobNodes(edges, 'a', 'b')).toBe(false);
    expect(canConnectJobNodes(edges, 'c', 'a')).toBe(false);
    expect(canConnectJobNodes(edges, 'a', 'c')).toBe(true);
    expect(canConnectJobNodes(edges, 'd', 'c')).toBe(true);
  });
});
