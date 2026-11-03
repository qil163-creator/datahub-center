import type { IntegrationFlow, IntegrationFlowNode } from './component-catalog';

import { describe, expect, it } from 'vitest';

import {
  inferIntegrationInputFields,
  integrationPortFields,
} from './field-hints';

function node(
  id: string,
  code: string,
  config: Record<string, unknown>,
): IntegrationFlowNode {
  return {
    id,
    type: 'integration-node',
    x: 0,
    y: 0,
    properties: { componentCode: code, toolCode: code, nodeJson: config },
  };
}
function chain(...nodes: IntegrationFlowNode[]): IntegrationFlow {
  return {
    nodes,
    edges: nodes
      .slice(1)
      .map((item, index) => ({
        sourceNodeId: nodes[index]!.id,
        targetNodeId: item.id,
      })),
  };
}
const source = () =>
  node('input', 'read-csv', {
    columns: [
      { name: 'id', type: 'LONG', nullable: false, precision: 18 },
      { name: 'raw', type: 'STRING' },
    ],
  });

describe('集成设计器保守字段提示', () => {
  it('字段选择按声明顺序传播别名和删除，不泄漏已删除字段或伪造转换类型', () => {
    const selected = node('select', 'column-map', {
      columnMappingMode: 'FIELDS',
      fieldSelections: [
        { name: 'raw', alias: 'station' },
        { name: 'id', alias: 'ID' },
      ],
      removedFields: [],
    });
    const graph = chain(source(), selected, node('out', 'insert-db', {}));
    expect(inferIntegrationInputFields(graph, 'out')).toEqual({
      complete: true,
      fields: [
        { name: 'station', dataType: 'STRING' },
        { name: 'ID', dataType: 'LONG', nullable: false, precision: 18 },
      ],
    });
    selected.properties.nodeJson = {
      columnMappingMode: 'FIELDS',
      fieldSelections: [{ name: 'id', alias: 'ID' }],
      removedFields: ['raw'],
    };
    expect(
      inferIntegrationInputFields(graph, 'out').fields.map((item) => item.name),
    ).toEqual(['ID']);
    for (const rule of [
      { name: 'id', alias: 'ID', type: 'DOUBLE' },
      { name: 'id', alias: 'ID', length: 0 },
      { name: 'missing' },
    ]) {
      selected.properties.nodeJson = {
        columnMappingMode: 'FIELDS',
        fieldSelections: [rule],
        removedFields: [],
      };
      expect(inferIntegrationInputFields(graph, 'out')).toEqual({
        complete: false,
        fields: [],
      });
    }
    selected.properties.nodeJson = { fieldSelections: [{ name: 'id' }] };
    expect(inferIntegrationInputFields(graph, 'out')).toEqual({
      complete: false,
      fields: [],
    });
  });
  it('多跳字段拆分和常量保留真实来源类型及新增字段', () => {
    const graph = chain(
      source(),
      node('split', 'field-split', {
        sourceField: 'raw',
        outputs: [{ name: 'station' }],
      }),
      node('constant', 'add-constant', {
        fields: [{ name: 'region', fieldType: 'STRING' }],
      }),
      node('out', 'insert-db', {}),
    );
    expect(inferIntegrationInputFields(graph, 'out')).toEqual({
      complete: true,
      fields: [
        { name: 'id', dataType: 'LONG', nullable: false, precision: 18 },
        { name: 'station', dataType: 'STRING' },
        { name: 'region', dataType: 'STRING' },
      ],
    });
  });
  it('复制覆盖规则读取同一个原始字段快照，保留小数类型精度', () => {
    const graph = chain(
      node('input', 'read-csv', {
        columns: [
          { name: 'a', type: 'DECIMAL', precision: 30, scale: 10 },
          { name: 'b', type: 'LONG' },
        ],
      }),
      node('copy', 'set-field-value', {
        rules: [
          { sourceField: 'a', targetField: 'b' },
          { sourceField: 'b', targetField: 'a' },
        ],
      }),
      node('out', 'insert-db', {}),
    );
    expect(inferIntegrationInputFields(graph, 'out').fields).toEqual([
      { name: 'a', dataType: 'LONG' },
      { name: 'b', dataType: 'DECIMAL', precision: 30, scale: 10 },
    ]);
  });
  it('字符串转换不能沿用原数值精度和时区，也不会把未知列假定为STRING', () => {
    const graph = chain(
      node('input', 'read-db', {
        selectedColumns: ['amount'],
        databaseId: '9007199254740993',
        tableName: 'measure',
      }),
      node('string', 'string-operation', {
        rules: [{ inputField: 'amount', outputField: 'text' }],
      }),
      node('out', 'insert-db', {}),
    );
    expect(inferIntegrationInputFields(graph, 'string').fields).toEqual([
      { name: 'amount' },
    ]);
    expect(inferIntegrationInputFields(graph, 'out').fields).toEqual([
      { name: 'amount' },
      { name: 'text', dataType: 'STRING', nullable: undefined },
    ]);
  });
  it('精确字段改名跨多跳生效，Java正则和脚本未知时不沿用旧字段', () => {
    const rename = node('rename', 'column-map', {
      regexColumnMapper: [{ fromPattern: '^raw$', toValue: 'station' }],
    });
    const graph = chain(source(), rename, node('out', 'insert-db', {}));
    expect(
      inferIntegrationInputFields(graph, 'out').fields.map((item) => item.name),
    ).toEqual(['id', 'station']);
    rename.properties.nodeJson = {
      regexColumnMapper: [{ fromPattern: '(?i)raw', toValue: 'station' }],
    };
    expect(inferIntegrationInputFields(graph, 'out')).toEqual({
      complete: false,
      fields: [],
    });
    expect(
      inferIntegrationInputFields(
        chain(
          source(),
          node('script', 'java-script', {}),
          node('out', 'insert-db', {}),
        ),
        'out',
      ).complete,
    ).toBe(false);
  });
  it('元数据提示必须匹配当前数据连接和表，自定义SQL不使用表字段推断结果', () => {
    const input = node('input', 'read-db', {
      databaseId: '9007199254740993',
      tableName: 'current',
      fieldHints: {
        databaseId: '9007199254740993',
        tableName: 'old',
        fields: [{ name: 'stale', dataType: 'LONG' }],
      },
    });
    const graph = chain(input, node('out', 'insert-db', {}));
    expect(inferIntegrationInputFields(graph, 'out').complete).toBe(false);
    (input.properties.nodeJson as Record<string, any>).fieldHints.tableName =
      'current';
    expect(inferIntegrationInputFields(graph, 'out').fields[0]?.name).toBe(
      'stale',
    );
    (input.properties.nodeJson as Record<string, any>).ifSql = 1;
    expect(inferIntegrationInputFields(graph, 'out').complete).toBe(false);
  });
  it('循环和多上游不能被错误合并为可确定字段契约', () => {
    const graph = chain(
      source(),
      node('pass', 'sort-records', {}),
      node('out', 'insert-db', {}),
    );
    graph.edges.push({ sourceNodeId: 'pass', targetNodeId: 'input' });
    // 输入组件有上游本身已不是可证明的合法输入。
    graph.nodes[0]!.properties.componentCode = 'sort-records';
    graph.nodes[0]!.properties.toolCode = 'sort-records';
    expect(inferIntegrationInputFields(graph, 'out').complete).toBe(false);
    graph.edges.push({ sourceNodeId: 'input', targetNodeId: 'out' });
    expect(inferIntegrationInputFields(graph, 'out').complete).toBe(false);
  });
  it('条件分发按连线端口传递各自字段，未知端口不能回落汇总字段', () => {
    const schema = {
      outputFields: [{ name: 'id' }, { name: 'raw' }],
      outputPorts: {
        yes: [{ name: 'id', dataType: 'LONG' }],
        no: [{ name: 'raw', dataType: 'STRING' }],
      },
    };
    expect(integrationPortFields(schema, { sourcePort: 'yes' })).toEqual([
      { name: 'id', dataType: 'LONG' },
    ]);
    expect(
      integrationPortFields(schema, { properties: { outputPort: 'no' } }),
    ).toEqual([{ name: 'raw', dataType: 'STRING' }]);
    expect(integrationPortFields(schema, { sourcePort: 'missing' })).toEqual(
      [],
    );
    expect(
      integrationPortFields(
        { outputFields: [{ name: 'legacy' }] },
        { sourcePort: 'out' },
      ),
    ).toEqual([{ name: 'legacy' }]);
    const branch = node('condition', 'data-condition', {
      branches: [
        { id: 'yes', fields: ['id'] },
        { id: 'no', fields: ['raw'] },
      ],
    });
    const graph = chain(source(), branch, node('out', 'insert-db', {}));
    graph.edges[1]!.sourcePort = 'no';
    expect(inferIntegrationInputFields(graph, 'out').fields).toEqual([
      { name: 'raw', dataType: 'STRING' },
    ]);
    graph.edges[1]!.sourcePort = 'missing';
    expect(inferIntegrationInputFields(graph, 'out')).toEqual({
      fields: [],
      complete: false,
    });
    graph.edges[1]!.sourcePort = 'yes';
    branch.properties.nodeJson = {
      branches: [
        {
          id: 'yes',
          fields: [{ name: 'id', alias: 'renamed', type: 'STRING' }],
        },
      ],
    };
    expect(inferIntegrationInputFields(graph, 'out')).toEqual({
      fields: [],
      complete: false,
    });
  });
});
