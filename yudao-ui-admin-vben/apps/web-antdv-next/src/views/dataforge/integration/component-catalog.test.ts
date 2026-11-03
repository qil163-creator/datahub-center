import { reactive } from 'vue';

import { describe, expect, it } from 'vitest';

import {
  componentCapability,
  componentOf,
  componentSupportsEngine,
  integrationComponents,
  newIntegrationNodeDefaults,
  nodeComponent,
  nodeConfiguration,
  parseIntegrationFlow,
} from './component-catalog';

describe('集成流程快照', () => {
  it('新建实时表输出默认增量更新，token始终作业启动初始化，历史配置不迁移', () => {
    expect(newIntegrationNodeDefaults(componentOf('insert-db')!, 'REALTIME')).toMatchObject({ dataSyncType: 2, batchSize: 1024 });
    expect(newIntegrationNodeDefaults(componentOf('insert-db')!, 'OFFLINE')).not.toHaveProperty('dataSyncType');
    for (const mode of ['OFFLINE', 'REALTIME'] as const)
      expect(newIntegrationNodeDefaults(componentOf('custom-token')!, mode)).toEqual({ workerState: 'BATCH', batchPolicy: 'PARTITION', maximumRows: 1000 });
    const old = { id: 'historical', type: 'integration-node', x: 0, y: 0, properties: { componentCode: 'insert-db', nodeJson: { dataSyncType: 1 } } };
    expect(nodeConfiguration(old)).toEqual({ dataSyncType: 1 });
  });
  it('flink离线严格开放12项且自定义token认证可用，旧服务不能多开放圈出的组件', () => {
    const capabilities = {
      engine: 'FLINK' as const,
      mode: 'OFFLINE' as const,
      components: integrationComponents.map((component) => ({
        componentCode: component.code,
        status: 'PARTIAL' as const,
        reason: '',
      })),
    };
    const allowed = integrationComponents
      .filter((component) =>
        componentSupportsEngine(component, 'FLINK', 'OFFLINE', capabilities),
      )
      .map((component) => component.code);
    expect(allowed).toEqual([
      'read-db',
      'read-kafka',
      'read-tdengine',
      'java-script',
      'custom-token',
      'data-clean',
      'field-split',
      'dedup-records',
      'row-to-column',
      'column-to-row',
      'value-map',
      'insert-db',
    ]);
    expect(
      componentSupportsEngine(
        componentOf('rest-api')!,
        'FLINK',
        'OFFLINE',
        capabilities,
      ),
    ).toBe(false);
    expect(
      componentSupportsEngine(
        componentOf('write-kafka')!,
        'FLINK',
        'OFFLINE',
        capabilities,
      ),
    ).toBe(false);
  });
  it('实时Flink沿用12项范围，其他引擎不能借参考组件扩展实时范围', () => {
    const capabilities = {
      engine: 'FLINK' as const,
      mode: 'REALTIME' as const,
      components: integrationComponents.map((component) => ({
        componentCode: component.code,
        status: 'PARTIAL' as const,
        reason: '',
      })),
    };
    expect(
      integrationComponents.filter((component) =>
        componentSupportsEngine(component, 'FLINK', 'REALTIME', capabilities),
      ),
    ).toHaveLength(12);
    expect(
      componentSupportsEngine(
        componentOf('read-rabbitmq')!,
        'FLINK',
        'REALTIME',
        capabilities,
      ),
    ).toBe(false);
    expect(
      componentSupportsEngine(
        componentOf('custom-token')!,
        'FLINK',
        'REALTIME',
        capabilities,
      ),
    ).toBe(true);
    expect(
      componentSupportsEngine(
        componentOf('read-db')!,
        'SPARK',
        'REALTIME',
        capabilities,
      ),
    ).toBe(false);
  });
  it('datax仅开放三项组件，即使旧服务仍返回文件连接器可用也不越过产品范围', () => {
    const capabilities = {
      engine: 'DATAX' as const,
      mode: 'OFFLINE' as const,
      components: integrationComponents.map((component) => ({
        componentCode: component.code,
        status: 'SUPPORTED' as const,
        reason: '',
      })),
    };
    expect(
      integrationComponents
        .filter((component) =>
          componentSupportsEngine(component, 'DATAX', 'OFFLINE', capabilities),
        )
        .map((component) => component.code),
    ).toEqual(['read-db', 'dedup-records', 'insert-db']);
  });
  it('保留49个组件与历史工具标识，不在前端推断引擎执行能力', () => {
    expect(integrationComponents).toHaveLength(49);
    expect(
      integrationComponents.filter((component) =>
        componentSupportsEngine(component, 'SPARK', 'OFFLINE'),
      ),
    ).toHaveLength(0);
    expect(componentOf('row-to-column')?.defaults?.mode).toBe('COL2ROW');
    expect(componentOf('column-to-row')?.defaults?.mode).toBe('ROW2COL');
  });
  it('仅依据同一引擎和模式的后端能力开放组件，缺失项保持关闭', () => {
    const capabilities = {
      engine: 'FLINK' as const,
      mode: 'REALTIME' as const,
      components: [
        {
          componentCode: 'field-split',
          status: 'SUPPORTED' as const,
          reason: '支持字段拆分。',
        },
        {
          componentCode: 'read-db',
          status: 'PARTIAL' as const,
          reason: '仅支持全量读取。',
        },
        {
          componentCode: 'read-kafka',
          status: 'UNAVAILABLE' as const,
          reason: '尚未提供实时订阅。',
        },
      ],
    };
    expect(
      componentSupportsEngine(
        componentOf('field-split')!,
        'FLINK',
        'REALTIME',
        capabilities,
      ),
    ).toBe(true);
    expect(
      componentCapability(
        componentOf('read-db')!,
        'FLINK',
        'REALTIME',
        capabilities,
      ),
    ).toEqual(capabilities.components[1]);
    expect(
      componentSupportsEngine(
        componentOf('read-kafka')!,
        'FLINK',
        'REALTIME',
        capabilities,
      ),
    ).toBe(false);
    expect(
      componentSupportsEngine(
        componentOf('read-excel')!,
        'FLINK',
        'REALTIME',
        capabilities,
      ),
    ).toBe(false);
    expect(
      componentSupportsEngine(
        componentOf('field-split')!,
        'SPARK',
        'REALTIME',
        capabilities,
      ),
    ).toBe(false);
    expect(
      componentSupportsEngine(
        componentOf('field-split')!,
        'FLINK',
        'OFFLINE',
        capabilities,
      ),
    ).toBe(false);
  });
  it('重复或未知能力状态不会意外开放组件', () => {
    const component = componentOf('read-db')!;
    const capability = {
      componentCode: component.code,
      status: 'SUPPORTED' as const,
      reason: '',
    };
    expect(
      componentSupportsEngine(component, 'SPARK', 'OFFLINE', {
        engine: 'SPARK',
        mode: 'OFFLINE',
        components: [capability, capability],
      }),
    ).toBe(false);
    expect(
      componentSupportsEngine(component, 'SPARK', 'OFFLINE', {
        engine: 'SPARK',
        mode: 'OFFLINE',
        components: [{ ...capability, status: 'UNKNOWN' as 'SUPPORTED' }],
      }),
    ).toBe(false);
  });
  it('保留历史节点、连线及未知根配置，规范旧连线引用', () => {
    const graph = {
      nodes: [
        {
          id: '9007199254740993',
          x: 20,
          y: 30,
          type: 'table',
          label: '表输入组件',
          properties: {
            nodeJson: {
              databaseId: '9007199254740999',
              selectedColumns: ['中文列'],
              vendor: { custom: true },
            },
            opaque: '保留',
          },
        },
        { id: 'out', x: 200, y: 30, properties: {} },
      ],
      edges: [
        {
          source: '9007199254740993',
          target: 'out',
          properties: { outputPort: 'reject', custom: 42 },
        },
      ],
      engineSettings: { version: '2026' },
    };
    const result = parseIntegrationFlow(JSON.stringify(graph));
    expect(result.nodes[0]?.properties).toEqual(graph.nodes[0]?.properties);
    expect(result.edges[0]).toMatchObject({
      sourceNodeId: '9007199254740993',
      targetNodeId: 'out',
      properties: { outputPort: 'reject', custom: 42 },
    });
    expect(result.engineSettings).toEqual(graph.engineSettings);
    expect(nodeComponent(result.nodes[0]!)?.code).toBe('read-db');
  });
  it('从响应式快照复制配置，取消编辑不污染原图', () => {
    const node = reactive({
      id: 'n',
      type: 'integration-node',
      x: 0,
      y: 0,
      properties: { nodeJson: { mappings: [{ source: 'id', target: 'ID' }] } },
    });
    const draft = nodeConfiguration(node);
    (draft.mappings as { target: string }[])[0]!.target = 'CHANGED';
    expect(node.properties.nodeJson.mappings[0]?.target).toBe('ID');
  });
  it('识别同一执行器对应的不同组件而不丢失行列方向', () => {
    const component = componentOf('column-to-row')!;
    expect(component.defaults?.mode).toBe('ROW2COL');
    const node = {
      id: 'n',
      type: 'integration-node',
      x: 0,
      y: 0,
      properties: {
        componentCode: component.code,
        toolCode: component.toolCode,
        name: '自定义名称',
        nodeJson: JSON.stringify(component.defaults),
      },
    };
    expect(nodeComponent(node)?.code).toBe('column-to-row');
    expect(nodeConfiguration(node)).toEqual({
      mode: 'ROW2COL',
      rowColumnVersion: 2,
      pivotMappings: [],
    });
    const legacy = {
      ...node,
      properties: {
        ...node.properties,
        nodeJson: JSON.stringify({ mode: 'COL2ROW' }),
      },
    };
    expect(nodeConfiguration(legacy)).toEqual({ mode: 'COL2ROW' });
  });
  it.each([
    {
      nodes: [
        { id: 'n', x: 0, y: 0 },
        { id: 'n', x: 1, y: 1 },
      ],
      edges: [],
    },
    {
      nodes: [{ id: 'n', x: 0, y: 0 }],
      edges: [{ source: 'n', target: 'missing' }],
    },
    { nodes: [{ id: 'n', x: '0', y: 0 }], edges: [] },
  ])('拒绝无法安全编辑的图而不静默丢弃节点', (graph) => {
    expect(() => parseIntegrationFlow(JSON.stringify(graph))).toThrow(/流程/);
  });
  it.each(['DATAX', 'SPARK'] as const)(
    '实时%s即使收到旧服务错误的可用能力也不能使用组件',
    (engine) => {
      const capabilities = {
        engine,
        mode: 'REALTIME' as const,
        components: [
          { componentCode: 'read-db', status: 'PARTIAL' as const, reason: '' },
        ],
      };
      expect(
        componentSupportsEngine(
          componentOf('read-db')!,
          engine,
          'REALTIME',
          capabilities,
        ),
      ).toBe(false);
    },
  );
});
