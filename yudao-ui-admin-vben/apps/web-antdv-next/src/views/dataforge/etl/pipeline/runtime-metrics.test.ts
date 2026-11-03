import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { describe, expect, it } from 'vitest';

import {
  edgeKey,
  edgeRuntimeText,
  findBottleneck,
  formatBytes,
  formatRatio,
  mapEdgesToDesign,
  summarizeRuntimeEdges,
  throughputTrend,
  withLiveCounts,
} from './runtime-metrics';

function metrics(
  overrides: Partial<DataforgeEtlApi.RuntimeMetrics> = {},
): DataforgeEtlApi.RuntimeMetrics {
  return {
    elapsedMillis: 10_000,
    finished: false,
    transferredRows: 0,
    transferredBytes: 0,
    bufferedBytes: 0,
    nodes: [],
    edges: [],
    series: [],
    ...overrides,
  };
}

function edge(
  sourceNo: string,
  targetNo: string,
  blockedMillis: number,
  queued = 0,
): DataforgeEtlApi.RuntimeEdge {
  return {
    sourceNo,
    targetNo,
    queued,
    capacity: 1000,
    delivered: 10,
    blockedMillis,
    starvedMillis: 0,
  };
}

describe('运行观测吞吐趋势', () => {
  it('按相邻采样点计算行/秒，计数回退按 0 处理，时间未前进的点跳过', () => {
    expect(
      throughputTrend([
        {
          elapsedMillis: 0,
          transferredRows: 0,
          transferredBytes: 0,
          bufferedBytes: 0,
        },
        {
          elapsedMillis: 3000,
          transferredRows: 900,
          transferredBytes: 0,
          bufferedBytes: 0,
        },
        {
          elapsedMillis: 3000,
          transferredRows: 950,
          transferredBytes: 0,
          bufferedBytes: 0,
        },
        {
          elapsedMillis: 5000,
          transferredRows: '1350',
          transferredBytes: 0,
          bufferedBytes: 0,
        },
        {
          elapsedMillis: 6000,
          transferredRows: 1000,
          transferredBytes: 0,
          bufferedBytes: 0,
        },
      ]),
    ).toEqual([
      { elapsedMillis: 3000, rowsPerSecond: 300 },
      { elapsedMillis: 5000, rowsPerSecond: 200 },
      { elapsedMillis: 6000, rowsPerSecond: 0 },
    ]);
    expect(throughputTrend(undefined)).toEqual([]);
  });
});

describe('连线背压与瓶颈', () => {
  it('按上游等待占比和运行中队列填充率划分压力等级', () => {
    const names = new Map([
      ['read', '读取源表'],
      ['write', '写入目标'],
    ]);
    const [high, medium, normal, full] = summarizeRuntimeEdges(
      metrics({
        edges: [
          edge('read', 'write', 6000),
          edge('read', 'clean', 2500),
          edge('clean', 'write', 100),
          edge('clean', 'audit', 0, 950),
        ],
      }),
      names,
    );
    expect(high).toMatchObject({
      sourceName: '读取源表',
      targetName: '写入目标',
      blockedRatio: 0.6,
      level: 'high',
    });
    expect(medium).toMatchObject({ blockedRatio: 0.25, level: 'medium' });
    expect(normal).toMatchObject({ targetName: '写入目标', level: 'normal' });
    expect(full).toMatchObject({ queued: 950, level: 'high' });
    const finished = summarizeRuntimeEdges(
      metrics({ finished: true, edges: [edge('clean', 'audit', 0, 950)] }),
    );
    expect(finished[0]?.level).toBe('normal');
    const unstarted = summarizeRuntimeEdges(
      metrics({ elapsedMillis: 0, edges: [edge('read', 'write', 0)] }),
    );
    expect(unstarted[0]).toMatchObject({ blockedRatio: null, level: 'normal' });
    expect(summarizeRuntimeEdges(null)).toEqual([]);
  });

  it('瓶颈取上游等待占比最高的连线，占比不足 20% 时不提示', () => {
    const edges = summarizeRuntimeEdges(
      metrics({
        edges: [
          edge('read', 'clean', 2500),
          edge('clean', 'write', 7000),
          edge('read', 'audit', 1000),
        ],
      }),
    );
    expect(findBottleneck(edges)?.targetNo).toBe('write');
    expect(
      findBottleneck(
        summarizeRuntimeEdges(metrics({ edges: [edge('read', 'write', 1999)] })),
      ),
    ).toBeUndefined();
  });
});

describe('实时计数与画布映射', () => {
  it('只为运行中节点合并实时计数，保留字符串形式的大数', () => {
    const records: DataforgeEtlApi.NodeRecord[] = [
      { id: 1, nodeNo: 'read', runStatus: 3, readCount: 0, writeCount: 500 },
      { id: 2, nodeNo: 'write', runStatus: 2 },
      { id: 3, nodeNo: 'audit', runStatus: 1 },
    ];
    const merged = withLiveCounts(
      records,
      metrics({
        nodes: [
          { nodeNo: 'read', readCount: 0, writeCount: 999 },
          {
            nodeNo: 'write',
            readCount: '9007199254740993',
            writeCount: 120,
          },
          { nodeNo: 'audit', readCount: 7, writeCount: 7 },
        ],
      }),
    );
    expect(merged[0]).toBe(records[0]);
    expect(merged[1]).toMatchObject({
      readCount: '9007199254740993',
      writeCount: 120,
    });
    expect(merged[2]).toBe(records[2]);
    expect(withLiveCounts(records, null)).toBe(records);
  });

  it('子流程展开连线按设计节点归并，忽略内部连线并保留压力最高的一条', () => {
    const records: DataforgeEtlApi.NodeRecord[] = [
      { nodeNo: 'read' },
      { nodeNo: 'call:input', designNodeNo: 'call' },
      { nodeNo: 'call:clean', designNodeNo: 'call' },
      { nodeNo: 'write' },
    ];
    const mapped = mapEdgesToDesign(
      summarizeRuntimeEdges(
        metrics({
          edges: [
            edge('read', 'call:input', 1000),
            edge('read', 'call:clean', 6000),
            edge('call:input', 'call:clean', 9000),
            edge('call:clean', 'write', 0),
          ],
        }),
      ),
      records,
    );
    expect([...mapped.keys()]).toEqual([
      edgeKey('read', 'call'),
      edgeKey('call', 'write'),
    ]);
    expect(mapped.get(edgeKey('read', 'call'))?.targetNo).toBe('call:clean');
  });
});

describe('展示格式化', () => {
  it('格式化连线标签、占比与字节', () => {
    const [summary] = summarizeRuntimeEdges(
      metrics({ edges: [edge('read', 'write', 2500, 37)] }),
    );
    expect(summary && edgeRuntimeText(summary)).toBe('队列 37/1000 · 等待 2.5 s');
    expect(formatRatio(0.254)).toBe('25%');
    expect(formatRatio(null)).toBe('-');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes('1572864')).toBe('1.5 MB');
    expect(formatBytes(undefined)).toBe('0 B');
  });
});
