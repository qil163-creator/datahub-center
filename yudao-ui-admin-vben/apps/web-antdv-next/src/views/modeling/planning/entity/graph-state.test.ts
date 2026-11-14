import { describe, expect, it } from 'vitest';

import { layoutEntityGraph } from './graph-state';

describe('entity relation graph layout', () => {
  it('places nodes left to right and keeps only edges between known nodes', () => {
    const result = layoutEntityGraph({
      centerKey: 'ENTITY:1',
      nodes: [
        { key: 'ENTITY:1', kind: 'ENTITY', refId: 1, name: '客户', code: 'customer', subtype: 'NORMAL' },
        { key: 'ENTITY:2', kind: 'ENTITY', refId: '9007199254740993', name: '会员', code: 'member', subtype: 'HIERARCHICAL' },
        { key: 'MODEL:9', kind: 'MODEL', refId: 9, name: '客户维度表', code: 'dim_customer', subtype: 'DIMENSION' },
      ],
      edges: [
        { source: 'ENTITY:1', target: 'ENTITY:2', relationType: 'INHERITANCE', label: '继承' },
        { source: 'ENTITY:1', target: 'MODEL:9', relationType: 'LOGIC_MODEL', label: '逻辑表' },
        { source: 'ENTITY:1', target: 'ENTITY:404', relationType: 'ASSOCIATION', label: '关联' },
      ],
    });

    expect(result.nodes.map((node) => node.key)).toEqual(['ENTITY:1', 'ENTITY:2', 'MODEL:9']);
    expect(result.nodes[0]?.center).toBe(true);
    expect(result.nodes[1]?.refId).toBe('9007199254740993');
    expect(result.nodes[1]?.subtitle).toBe('member · 层级对象');
    expect(result.nodes[2]?.subtitle).toBe('dim_customer · 维度表');
    expect(result.nodes[1]!.x).toBeGreaterThan(result.nodes[0]!.x);
    expect(result.edges.map((edge) => edge.label)).toEqual(['继承', '逻辑表']);
  });
});
