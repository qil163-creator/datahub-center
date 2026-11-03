import type { IntegrationFlow } from './component-catalog';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { IntegrationApi } from '#/api/dataforge/integration';

import { getSourcePort } from '../etl/pipeline/graph-contract';
import { nodeComponent, nodeConfiguration } from './component-catalog';

/** 有分支字段契约时禁止回落到节点汇总字段，以免把另一分支的列提供给下游。 */
export function integrationPortFields(
  schema: IntegrationApi.NodeSchema | undefined,
  edge: Record<string, any>,
): IntegrationApi.SchemaField[] | undefined {
  if (!schema) return undefined;
  if (schema.outputPorts && Object.keys(schema.outputPorts).length > 0)
    return schema.outputPorts[getSourcePort(edge)] ?? [];
  return schema.outputFields;
}

type Field = DataforgeEtlApi.FieldMeta;
export interface IntegrationFieldHints {
  fields: Field[];
  complete: boolean;
}
const unknown = (): IntegrationFieldHints => ({ fields: [], complete: false });
const list = (value: unknown): Record<string, any>[] =>
  Array.isArray(value)
    ? value.filter((item) => item && typeof item === 'object')
    : [];
const text = (value: unknown) => (typeof value === 'string' ? value : '');
const field = (value: Record<string, any>): Field => ({
  name: text(value.name),
  ...(typeof (value.dataType ?? value.type) === 'string'
    ? { dataType: value.dataType ?? value.type }
    : {}),
  ...Object.fromEntries(
    ['nullable', 'precision', 'scale', 'timezone', 'length', 'sourceName']
      .filter((key) => value[key] !== undefined)
      .map((key) => [key, value[key]]),
  ),
});

/** 只对快照中可证明的声明提供编辑提示；未知引擎语义不补 STRING，也不代替后端字段契约。 */
export function inferIntegrationInputFields(
  flow: IntegrationFlow,
  nodeId: string,
): IntegrationFieldHints {
  if (flow.nodes.length > 200 || flow.edges.length > 400) return unknown();
  const nodes = new Map(flow.nodes.map((node) => [node.id, node]));
  const parents = new Map(
    flow.nodes.map((node) => [node.id, [] as IntegrationFlow['edges']]),
  );
  for (const edge of flow.edges) parents.get(edge.targetNodeId)?.push(edge);
  const active = new Set<string>();
  const memo = new Map<string, IntegrationFieldHints>();
  function input(id: string): IntegrationFieldHints {
    const sources = parents.get(id) ?? [];
    if (sources.length !== 1)
      return sources.length === 0 ? { fields: [], complete: true } : unknown();
    const edge = sources[0]!;
    const result = output(edge.sourceNodeId);
    const parent = nodes.get(edge.sourceNodeId);
    if (!parent || nodeComponent(parent)?.toolCode !== 'data-condition')
      return result;
    const config = nodeConfiguration(parent);
    const branch = list(config.branches).find(
      (item) => item.id === getSourcePort(edge),
    );
    if (!branch) return unknown();
    if (branch.fields === undefined) return result;
    if (
      !result.complete ||
      !Array.isArray(branch.fields) ||
      branch.fields.length === 0
    )
      return unknown();
    const selected: Field[] = [];
    for (const value of branch.fields) {
      // 分支契约只接受字段名；对象中的别名、类型等不能假定已经执行。
      if (typeof value !== 'string') return unknown();
      const name = value;
      const source = result.fields.find((item) => item.name === name);
      if (!source || selected.some((item) => item.name === name))
        return unknown();
      selected.push({ ...source });
    }
    return { fields: selected, complete: true };
  }
  function output(id: string): IntegrationFieldHints {
    const cached = memo.get(id);
    if (cached) return cached;
    if (active.has(id) || active.size > 64) return unknown();
    const node = nodes.get(id);
    if (!node) return unknown();
    active.add(id);
    let result: IntegrationFieldHints;
    try {
      const config = nodeConfiguration(node);
      const component = nodeComponent(node);
      const code = component?.toolCode ?? text(node.properties.toolCode);
      if (component?.group === 'input') {
        if (config.ifSql === 1) result = unknown();
        else {
          const hints = config.fieldHints as
            | undefined
            | { databaseId?: unknown; fields?: unknown; tableName?: unknown };
          const currentHints =
            hints &&
            String(hints.databaseId) === String(config.databaseId) &&
            hints.tableName === config.tableName
              ? list(hints.fields)
              : [];
          let fields = (
            currentHints.length > 0 ? currentHints : list(config.columns)
          )
            .map((item) => field(item))
            .filter((item) => item.name);
          if (Array.isArray(config.selectedColumns)) {
            const selected = config.selectedColumns.filter(
              (name): name is string => typeof name === 'string',
            );
            const byName = new Map(fields.map((item) => [item.name, item]));
            fields = selected.map((name) => byName.get(name) ?? { name });
          }
          result = { fields, complete: fields.length > 0 };
        }
      } else result = transform(code, config, input(id));
    } catch {
      result = unknown();
    }
    active.delete(id);
    memo.set(id, result);
    return result;
  }
  return input(nodeId);
}

function transform(
  code: string,
  config: Record<string, any>,
  input: IntegrationFieldHints,
): IntegrationFieldHints {
  if (!input.complete) return unknown();
  const original = new Map(
    input.fields.map((item) => [item.name, { ...item }]),
  );
  const result = new Map(original);
  const append = (name: unknown, dataType?: string) => {
    if (text(name))
      result.set(text(name), {
        name: text(name),
        ...(dataType ? { dataType } : {}),
      });
  };
  if (['data-condition', 'data-filter', 'sort-records'].includes(code))
    return { fields: [...result.values()], complete: true };
  if (
    ['set-field-value', 'string-operation', 'string-replace'].includes(code)
  ) {
    for (const rule of list(config.rules)) {
      const source = original.get(rule.sourceField ?? rule.inputField);
      const target = text(rule.targetField ?? rule.outputField) || source?.name;
      if (!source || !target) return unknown();
      result.set(
        target,
        code === 'set-field-value'
          ? { ...source, name: target }
          : { name: target, dataType: 'STRING', nullable: source.nullable },
      );
    }
  } else if (code === 'field-split') {
    if (!config.keepSource) result.delete(config.sourceField);
    list(config.outputs).forEach((item) => append(item.name, 'STRING'));
  } else if (code === 'add-constant')
    list(config.fields).forEach((item) =>
      append(item.name, text(item.fieldType) || 'STRING'),
    );
  else if (code === 'field-derive') append(config.targetField, 'STRING');
  else if (
    ['id-generator', 'number-range', 'split-to-rows', 'value-map'].includes(
      code,
    )
  )
    append(config.targetField || config.sourceField);
  else if (code === 'calculator')
    list(config.rules).forEach((item) => append(item.targetField));
  else if (code === 'data-clean') {
    if (config.executionMode === 'GOVERNANCE') return unknown();
    if (config.enableFieldRemoval)
      text(config.fieldsToRemove)
        .split(',')
        .map((name) => name.trim())
        .forEach((name) => result.delete(name));
    if (config.enableFormatting)
      for (const rule of list(config.formatRules))
        append(rule.fieldName ?? rule.field);
    if (config.enableNullHandling && config.nullHandlingStrategy !== 'REMOVE')
      for (const rule of list(config.defaultValues))
        append(rule.fieldName ?? rule.field);
  } else if (code === 'column-map') {
    const renamed = new Map<string, Field>();
    if (config.columnMappingMode === 'FIELDS') {
      if (
        !Array.isArray(config.fieldSelections) ||
        config.fieldSelections.length === 0 ||
        !Array.isArray(config.removedFields ?? [])
      )
        return unknown();
      const selections = list(config.fieldSelections);
      if (selections.length !== config.fieldSelections.length) return unknown();
      const removed: unknown[] = config.removedFields ?? [];
      if (
        removed.some((name) => typeof name !== 'string' || !original.has(name))
      )
        return unknown();
      for (const selection of selections) {
        const source = original.get(selection.name);
        if (
          !source ||
          (selection.alias !== null &&
            selection.alias !== undefined &&
            typeof selection.alias !== 'string')
        )
          return unknown();
        if (removed.includes(selection.name)) continue;
        // 只有已证明的纯选择、别名和删除可以本地推导，转换配置交由后端确认。
        const requestedType = text(selection.type);
        if (
          (requestedType &&
            requestedType.toLowerCase() !== source.dataType?.toLowerCase()) ||
          [selection.length, selection.precision].some(
            (value) =>
              value !== null &&
              value !== undefined &&
              String(value).trim() !== '',
          )
        )
          return unknown();
        const alias = text(selection.alias);
        const name = alias.trim() ? alias : source.name!;
        if (renamed.has(name)) return unknown();
        renamed.set(name, { ...source, name });
      }
      return renamed.size > 0
        ? { fields: [...renamed.values()], complete: true }
        : unknown();
    }
    if (
      config.fieldSelections !== undefined ||
      config.removedFields !== undefined
    )
      return unknown();
    for (const source of input.fields) {
      let name = source.name!;
      let removed = false;
      for (const rule of list(config.regexColumnMapper)) {
        // 仅接受 Java/JavaScript 均无歧义的完整字面字段匹配，不在浏览器模拟 Java 正则。
        const pattern = text(rule.fromPattern);
        if (!/^\^[\p{L}\p{N}_ -]+\$$/u.test(pattern)) return unknown();
        if (pattern.slice(1, -1) !== name) continue;
        const replacement = text(rule.toValue);
        if (replacement.includes('$') || replacement.includes('\\'))
          return unknown();
        removed = !replacement;
        name = replacement;
        break;
      }
      if (!removed) {
        if (renamed.has(name)) return unknown();
        renamed.set(name, { ...source, name });
      }
    }
    return { fields: [...renamed.values()], complete: true };
  } else return unknown();
  return { fields: [...result.values()], complete: true };
}
