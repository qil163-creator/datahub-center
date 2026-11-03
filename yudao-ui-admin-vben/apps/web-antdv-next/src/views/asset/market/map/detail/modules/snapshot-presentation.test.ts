import type { ImportedAssetApi } from '#/api/asset/dataasset/imported';

import { describe, expect, it } from 'vitest';

import { lineageGraph } from './lineage-data';
import { qualityDimensions, qualityTrend } from './quality-presentation';

describe('导入来源质量记录', () => {
  const snapshot: ImportedAssetApi.Snapshot = {
    sourceAssetId: '49',
    sourceDataCount: 12,
    importedRowCount: 12,
    complete: true,
    quality: {
      evaluations: [{ sourceId: '16', dimensionType: '1' }],
      statistics: {
        dimensions: [
          {
            dimensionType: '1',
            total: '9007199254740993',
            problemTotal: '0',
            proportion: '0.00000000000000001',
            yoyIncrease: '0',
          },
        ],
        trend: {
          title: ['2026-10-21', '2026-10-22'],
          value: [null, '99.99999999999999999'],
        },
      },
    },
  };
  it('保留大数量、十进制比例和历史评分原文', () => {
    const completeness = qualityDimensions(snapshot).find(
      (row) => row.value === 'COMPLETENESS',
    );
    expect(completeness).toMatchObject({
      ruleCount: 1,
      totalCount: '9007199254740993',
      issueCount: '0',
      proportion: '0.00000000000000001',
    });
    expect(qualityTrend(snapshot)).toEqual([
      { date: '2026-10-21', score: null },
      { date: '2026-10-22', score: '99.99999999999999999' },
    ]);
  });
  it('缺少评估数据时保持缺失，不生成评分或零问题结果', () => {
    expect(qualityTrend()).toEqual([]);
    expect(
      qualityDimensions().every(
        (row) => row.issueCount === undefined && row.proportion === undefined,
      ),
    ).toBe(true);
    expect(
      qualityDimensions(snapshot).find((row) => row.value === 'ACCURACY')
        ?.ruleCount,
    ).toBeUndefined();
  });
});

describe('导入血缘拓扑', () => {
  it('按不透明身份连接真实节点，并保留全局/局部覆盖标记', () => {
    const graph = lineageGraph({
      tables: [
        { graphId: 'node-a', label: '源表' },
        { graphId: 'node-c', label: '结果表' },
      ],
      tasks: [{ graphId: 'node-b', label: '转换任务' }],
      rels: [
        { graphId: 'edge-1', source: 'node-a', target: 'node-b' },
        { graphId: 'edge-2', startNodeId: 'node-b', endNodeId: 'node-c' },
        { id: 'missing', source: 'unknown', target: 'node-c' },
      ],
      scope: { globalLineageSeed: true },
      hasMore: true,
    });
    expect(graph.nodes).toHaveLength(3);
    expect(graph.edges).toHaveLength(2);
    expect(graph.global).toBe(true);
    expect(graph.hasMore).toBe(true);
    const [a, c, b] = graph.nodes;
    expect(a!.x).toBeLessThan(b!.x);
    expect(b!.x).toBeLessThan(c!.x);
  });
  it('缺失图返回空白；环状来源保留节点且布局有界', () => {
    expect(lineageGraph().nodes).toEqual([]);
    const graph = lineageGraph({
      tables: [{ id: 'a' }, { id: 'b' }],
      rels: [
        { source: 'a', target: 'b' },
        { source: 'b', target: 'a' },
      ],
    });
    expect(graph.nodes).toHaveLength(2);
    expect(
      graph.nodes.every(
        (node) => Number.isFinite(node.x) && Number.isFinite(node.y),
      ),
    ).toBe(true);
  });
});
