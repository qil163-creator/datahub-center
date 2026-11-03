import { describe, expect, it } from 'vitest';

import {
  createsCycle,
  getOutputPorts,
  getSourcePort,
  getVisualSourcePort,
  serializeEdgePort,
} from './graph-contract';

describe('etl 持久化端口兼容性', () => {
  it('旧图输出与条件分支可无损载入和保存', () => {
    expect(getSourcePort({})).toBe('main');
    expect(getSourcePort({ sourcePort: 'out' })).toBe('main');
    const edge = { properties: { outputPort: 'branch-1' } };
    expect(getVisualSourcePort(edge, 'data-condition')).toBe('out');
    expect(serializeEdgePort('out', edge.properties)).toBe('branch-1');
  });

  it('质量分流端口优先于遗留 outputPort，并依处置策略切换', () => {
    const edge = { sourcePort: 'reject', properties: { outputPort: 'main' } };
    expect(getVisualSourcePort(edge, 'quality-check')).toBe('reject');
    expect(serializeEdgePort('warn', { outputPort: 'main' })).toBe('warn');
    expect(getOutputPorts('quality-check')).toEqual(['pass', 'warn', 'reject']);
    expect(
      getOutputPorts('quality-check', { disposition: 'KEEP_TAGGED' }),
    ).toEqual(['main']);
  });

  it('阻止多级环路，同时允许 DAG 合流', () => {
    const edges = [
      { sourceNodeId: 'a', targetNodeId: 'b' },
      { sourceNodeId: 'b', targetNodeId: 'c' },
    ];
    expect(createsCycle(edges, 'c', 'a')).toBe(true);
    expect(createsCycle(edges, 'a', 'a')).toBe(true);
    expect(createsCycle(edges, 'a', 'c')).toBe(false);
    expect(createsCycle(edges, 'a', 'b')).toBe(false);
  });
});
