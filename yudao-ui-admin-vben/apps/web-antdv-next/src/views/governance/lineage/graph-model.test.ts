import type { GovernanceLineageApi } from '#/api/governance/lineage';

import { describe, expect, it } from 'vitest';

import {
  layoutColumnGraph,
  layoutTableGraph,
  mergeGraph,
  nodeOptionLabel,
  nodeSubtitle,
  nodeTitle,
} from './graph-model';

const BIG = '9007199254740993';

function table(id: GovernanceLineageApi.Id, tableName: string, depth: number, extra = {}) {
  return {
    id,
    nodeType: 'TABLE' as const,
    datasourceId: BIG,
    schemaName: 'ods',
    tableName,
    depth,
    upstreamExpandable: true,
    downstreamExpandable: true,
    ...extra,
  };
}

describe('lineage graph model', () => {
  it('lays out from upstream to downstream and keeps lossless ids as keys', () => {
    const canvas = layoutTableGraph({
      rootNodeId: BIG,
      nodes: [table('1', 'src', -1), table(BIG, 'mid', 0), table('3', 'dst', 1)],
      edges: [
        { upstreamNodeId: '1', downstreamNodeId: BIG, origins: ['PARSED'], sources: [] },
        { upstreamNodeId: BIG, downstreamNodeId: '3', origins: ['MANUAL'], sources: [] },
      ],
    });
    const byKey = new Map(canvas.nodes.map((node) => [node.key, node]));
    expect(byKey.get(BIG)?.root).toBe(true);
    expect(byKey.get('1')!.x).toBeLessThan(byKey.get(BIG)!.x);
    expect(byKey.get(BIG)!.x).toBeLessThan(byKey.get('3')!.x);
    expect(canvas.edges.find((edge) => edge.target === '3')?.dashed).toBe(true);
    expect(canvas.edges.find((edge) => edge.target === BIG)?.dashed).toBe(false);
  });

  it('drops edges whose endpoints were truncated away', () => {
    const canvas = layoutTableGraph({
      rootNodeId: '1',
      nodes: [table('1', 'a', 0)],
      edges: [{ upstreamNodeId: '1', downstreamNodeId: '404', origins: ['PARSED'], sources: [] }],
      truncated: true,
    });
    expect(canvas.edges).toHaveLength(0);
  });

  it('marks ambiguous and indirect column edges as dashed and labels transforms', () => {
    const canvas = layoutColumnGraph({
      rootNodeId: '2',
      rootColumn: 'amount',
      nodes: [
        { nodeId: '1', column: 'price', depth: -1 },
        { nodeId: '1', column: 'status', depth: -1 },
        { nodeId: '2', column: 'amount', depth: 0 },
        { nodeId: '2', column: '*', depth: 0 },
      ],
      edges: [
        {
          upstreamNodeId: '1', upstreamColumn: 'price', downstreamNodeId: '2', downstreamColumn: 'amount',
          transformType: 'DIRECT', transformSubtype: 'TRANSFORMATION', sources: [],
        },
        {
          upstreamNodeId: '1', upstreamColumn: 'status', downstreamNodeId: '2', downstreamColumn: '*',
          transformType: 'INDIRECT', transformSubtype: 'FILTER', sources: [],
        },
      ],
    });
    expect(canvas.nodes.find((node) => node.key === '2:amount')?.root).toBe(true);
    expect(canvas.nodes.find((node) => node.key === '2:*')?.title).toBe('整表');
    expect(canvas.edges.find((edge) => edge.source === '1:price')).toMatchObject({ dashed: false, label: '表达式' });
    expect(canvas.edges.find((edge) => edge.source === '1:status')).toMatchObject({ dashed: true, label: '过滤条件' });
  });

  it('merges expanded results without duplicating nodes or edges', () => {
    const base: GovernanceLineageApi.Graph = {
      rootNodeId: '1',
      nodes: [table('1', 'a', 0), table('2', 'b', 1)],
      edges: [{ upstreamNodeId: '1', downstreamNodeId: '2', origins: ['PARSED'], sources: [] }],
    };
    const extra: GovernanceLineageApi.Graph = {
      rootNodeId: '2',
      nodes: [table('2', 'b', 0, { downstreamExpandable: false }), table('3', 'c', 1)],
      edges: [
        { upstreamNodeId: '1', downstreamNodeId: '2', origins: ['PARSED'], sources: [] },
        { upstreamNodeId: '2', downstreamNodeId: '3', origins: ['RUNTIME'], sources: [] },
      ],
      truncated: true,
    };
    const merged = mergeGraph(base, extra);
    expect(merged.rootNodeId).toBe('1');
    expect(merged.nodes.map((node) => String(node.id))).toEqual(['1', '2', '3']);
    expect(merged.nodes.find((node) => node.id === '2')?.depth).toBe(1);
    expect(merged.nodes.find((node) => node.id === '2')?.downstreamExpandable).toBe(false);
    expect(merged.edges).toHaveLength(2);
    expect(merged.truncated).toBe(true);
  });

  it('describes tables and external datasets', () => {
    const business = { id: '1', nodeType: 'TABLE' as const, schemaName: 'dw', tableName: 'fact', businessName: '订单事实' };
    expect(nodeTitle(business)).toBe('订单事实');
    expect(nodeSubtitle(business)).toBe('dw.fact');
    expect(nodeOptionLabel(business)).toBe('dw.fact（订单事实）');
    const external = { id: '2', nodeType: 'EXTERNAL' as const, externalKind: 'KAFKA', externalKey: 'read-kafka:topic=o', displayName: '订单流' };
    expect(nodeTitle(external)).toBe('订单流');
    expect(nodeSubtitle(external)).toBe('KAFKA · read-kafka:topic=o');
  });
});
