import type { IntegrationApi } from '#/api/dataforge/integration';
import type {
  IntegrationComponentCapabilities,
  IntegrationComponentCapability,
} from '#/api/dataforge/integration/component-capabilities';

export interface IntegrationComponent {
  code: string;
  name: string;
  icon: string;
  group: 'control' | 'input' | 'output' | 'script' | 'transform';
  toolCode: string;
  defaults?: Record<string, unknown>;
}
const make = (
  group: IntegrationComponent['group'],
  code: string,
  name: string,
  toolCode = code,
  defaults?: Record<string, unknown>,
): IntegrationComponent => ({
  group,
  code,
  name,
  toolCode,
  defaults,
  icon: `/assets/qdata-integration/palette-${code}.png`,
});
/** 离线集成页面的引擎组件范围，与执行端校验保持一致。 */
export const flinkComponentCodes = new Set([
  'column-to-row',
  'custom-token',
  'data-clean',
  'dedup-records',
  'field-split',
  'insert-db',
  'java-script',
  'read-db',
  'read-kafka',
  'read-tdengine',
  'row-to-column',
  'value-map',
]);
export function componentCapability(
  component: IntegrationComponent,
  engine?: IntegrationApi.Engine,
  mode?: IntegrationApi.Mode,
  capabilities?: IntegrationComponentCapabilities,
): IntegrationComponentCapability {
  const unavailable: IntegrationComponentCapability = {
    componentCode: component.code,
    status: 'UNAVAILABLE',
    reason: '当前组件的可用性尚未确认，请刷新后重试。',
  };
  if (mode === 'REALTIME' && engine !== 'FLINK')
    return { ...unavailable, reason: '实时任务只能使用Flink执行引擎。' };
  if (
    engine === 'DATAX' &&
    mode === 'OFFLINE' &&
    !['dedup-records', 'insert-db', 'read-db'].includes(component.code)
  ) {
    return {
      ...unavailable,
      reason: 'DataX离线任务仅支持表输入、去除重复记录和表输出。',
    };
  }
  if (engine === 'FLINK' && !flinkComponentCodes.has(component.code)) {
    return {
      ...unavailable,
      reason: `Flink${mode === 'REALTIME' ? '实时' : '离线'}任务不支持此组件。`,
    };
  }
  if (
    !engine ||
    !mode ||
    capabilities?.engine !== engine ||
    capabilities.mode !== mode
  )
    return unavailable;
  const matches = Array.isArray(capabilities.components)
    ? capabilities.components.filter(
        (item) => item.componentCode === component.code,
      )
    : [];
  const capability = matches.length === 1 ? matches[0] : undefined;
  return capability &&
    ['PARTIAL', 'SUPPORTED', 'UNAVAILABLE'].includes(capability.status)
    ? {
        ...capability,
        reason:
          capability.reason ||
          (capability.status === 'SUPPORTED'
            ? '支持当前引擎和任务模式。'
            : '请先执行任务检查，确认配置可用。'),
      }
    : unavailable;
}
export const componentSupportsEngine = (
  component: IntegrationComponent,
  engine?: IntegrationApi.Engine,
  mode?: IntegrationApi.Mode,
  capabilities?: IntegrationComponentCapabilities,
) =>
  componentCapability(component, engine, mode, capabilities).status !==
  'UNAVAILABLE';
/** 名称、顺序和图标以 qData 参考页为准，执行标识复用 ETL 组件契约。 */
export const integrationComponents: IntegrationComponent[] = [
  make('input', 'read-db', '表输入组件'),
  make('input', 'read-odps', 'ODPS输入组件'),
  make('input', 'read-excel', 'Excel文件输入组件'),
  make('input', 'read-csv', 'CSV输入组件'),
  make('input', 'read-json', 'JSONL输入组件'),
  make('input', 'read-kafka', 'Kafka输入组件'),
  make('input', 'read-hive', 'Hive输入组件'),
  make('input', 'read-hdfs', 'HDFS输入组件'),
  make('input', 'rest-api', 'API输入组件'),
  make('input', 'read-mongodb', 'MongoDB输入组件'),
  make('input', 'read-redis', 'Redis输入组件'),
  make('input', 'read-rabbitmq', 'RabbitMQ输入组件'),
  make('input', 'read-tdengine', 'TDengine输入组件'),
  make('input', 'read-rocketmq', 'RocketMQ输入组件'),
  make('input', 'read-ftp', 'FTP输入组件'),
  make('input', 'read-webservice', 'WebService输入组件'),
  make('script', 'java-script', '执行Java脚本'),
  make('script', 'custom-token', '自定义token认证'),
  make('transform', 'data-clean', '转换组件'),
  make('transform', 'multi-join', 'Join组件'),
  make('transform', 'sort-records', '排序记录'),
  make('transform', 'field-split', '字段拆分'),
  make('transform', 'field-derive', '字段派生器'),
  make('transform', 'dedup-records', '去除重复记录', 'data-clean', {
    enableDeduplication: true,
    deduplicationStrategy: 'KEY_FIELDS',
    keyFields: [],
  }),
  make('transform', 'row-to-column', '行转列', 'row-column', {
    mode: 'COL2ROW',
    rowColumnVersion: 2,
    unpivotMappings: [],
  }),
  make('transform', 'column-to-row', '列转行', 'row-column', {
    mode: 'ROW2COL',
    rowColumnVersion: 2,
    pivotMappings: [],
  }),
  make('transform', 'data-encrypt', '字段加密'),
  make('transform', 'data-decrypt', '字段解密'),
  make('transform', 'id-generator', 'ID生成器'),
  make('transform', 'calculator', '计算器'),
  make('transform', 'value-map', '值映射'),
  make('transform', 'add-constant', '增加常量'),
  make('transform', 'number-range', '数值范围', 'number-range', {
    ranges: [
      { min: '', max: '5', value: 'Less than 5' },
      { min: '5', max: '10', value: '5-10' },
      { min: '10', max: '', value: 'More than 10' },
    ],
  }),
  make('transform', 'string-operation', '字符串操作'),
  make('transform', 'string-replace', '字符串替换'),
  make('transform', 'column-map', '字段选择、修改'),
  make('transform', 'set-field-value', '设置字段值'),
  make('control', 'data-condition', '条件分发'),
  make('control', 'sub-pipeline', '子流程'),
  make('output', 'insert-db', '表输出组件'),
  make('output', 'write-odps', 'ODPS输出组件'),
  make('output', 'write-hive', 'Hive输出组件'),
  make('output', 'write-hdfs', 'HDFS输出组件'),
  make('output', 'write-mongodb', 'MongoDB输出组件'),
  make('output', 'write-redis', 'Redis输出组件'),
  make('output', 'write-rabbitmq', 'RabbitMQ输出组件'),
  make('output', 'write-kafka', 'kafka输出组件'),
  make('output', 'write-rocketmq', 'RocketMQ输出组件'),
  make('output', 'write-ftp', 'FTP输出组件'),
];
export const componentGroups = [
  { key: 'input', name: '输入', icon: 'read-db' },
  { key: 'script', name: '脚本执行', icon: 'script-group' },
  { key: 'transform', name: '转换', icon: 'data-clean' },
  { key: 'control', name: '流程控制', icon: 'data-condition' },
  { key: 'output', name: '输出', icon: 'insert-db' },
] as const;
export const componentOf = (code?: string) =>
  integrationComponents.find((item) => item.code === code);
/** 仅用于新建节点，历史快照由 nodeConfiguration 原样读取。 */
export function newIntegrationNodeDefaults(
  component: IntegrationComponent,
  mode?: IntegrationApi.Mode,
): Record<string, unknown> {
  const defaults = structuredClone(component.defaults ?? {});
  if (component.code === 'insert-db' && mode === 'REALTIME')
    return { ...defaults, dataSyncType: 2, batchSize: 1024, targetTruncate: false };
  if (component.code === 'custom-token')
    return { ...defaults, workerState: 'BATCH', batchPolicy: 'PARTITION', maximumRows: 1000 };
  return defaults;
}
export interface IntegrationFlowNode {
  id: string;
  type: string;
  x: number;
  y: number;
  properties: Record<string, unknown> & {
    componentCode?: string;
    name?: string;
    nodeJson?: Record<string, unknown> | string;
    toolCode?: string;
    toolType?: number;
  };
  [key: string]: unknown;
}
export interface IntegrationFlowEdge {
  id?: string;
  sourceNodeId: string;
  targetNodeId: string;
  sourcePort?: string;
  targetPort?: string;
  properties?: Record<string, unknown>;
  [key: string]: unknown;
}
export interface IntegrationFlow {
  nodes: IntegrationFlowNode[];
  edges: IntegrationFlowEdge[];
  [key: string]: unknown;
}
/** 保留未知节点属性，避免编辑导入快照时丢失配置。 */
export function parseIntegrationFlow(json?: string): IntegrationFlow {
  const value: unknown = JSON.parse(json || '{"nodes":[],"edges":[]}');
  if (!value || typeof value !== 'object')
    throw new Error('流程内容必须为对象');
  const data = value as Record<string, unknown>;
  if (!Array.isArray(data.nodes) || !Array.isArray(data.edges))
    throw new Error('流程缺少节点或连线集合');
  const ids = new Set<string>();
  const nodes = data.nodes.map((raw: Record<string, unknown>) => {
    if (typeof raw.id !== 'string' || ids.has(raw.id))
      throw new Error('流程节点编号无效或重复');
    ids.add(raw.id);
    if (
      typeof raw.x !== 'number' ||
      typeof raw.y !== 'number' ||
      !Number.isFinite(raw.x) ||
      !Number.isFinite(raw.y)
    )
      throw new Error('流程节点坐标无效');
    const properties =
      raw.properties && typeof raw.properties === 'object'
        ? raw.properties
        : {};
    return {
      ...raw,
      type: String(raw.type ?? 'integration-node'),
      properties,
    } as IntegrationFlowNode;
  });
  const edges = data.edges.map((raw: Record<string, unknown>) => {
    const sourceNodeId = String(raw.sourceNodeId ?? raw.source ?? '');
    const targetNodeId = String(raw.targetNodeId ?? raw.target ?? '');
    if (!ids.has(sourceNodeId) || !ids.has(targetNodeId))
      throw new Error('流程存在悬空连线');
    return { ...raw, sourceNodeId, targetNodeId } as IntegrationFlowEdge;
  });
  return { ...data, nodes, edges };
}
export function nodeConfiguration(
  node: IntegrationFlowNode,
): Record<string, unknown> {
  const config = node.properties.nodeJson;
  const value: unknown =
    typeof config === 'string' ? JSON.parse(config || '{}') : (config ?? {});
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('节点配置必须为对象');
  return JSON.parse(JSON.stringify(value)) as Record<string, unknown>;
}
export function nodeComponent(node: IntegrationFlowNode) {
  return (
    componentOf(node.properties.componentCode) ??
    integrationComponents.find(
      (item) => item.name === (node.properties.name ?? node.label),
    ) ??
    integrationComponents.find(
      (item) => item.toolCode === node.properties.toolCode,
    )
  );
}
