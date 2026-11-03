/** 字段映射只保存可执行的来源/目标/启用三元组，两列排序与勾选属于编辑交互。 */
export interface FieldMapping {
  source: string;
  target: string;
  enabled: boolean;
}
export interface MappingField {
  name: string;
  dataType?: null | string;
}
export interface MappingColumn extends MappingField {
  key: string;
  checked: boolean;
  exists: boolean;
}
export interface MappingColumns {
  source: MappingColumn[];
  target: MappingColumn[];
}
/** 保存双列各自的勾选/顺序；执行器仍只消费 fieldMappings。 */
export interface FieldMappingLayout {
  version: 1;
  source: { checked: boolean; name: string; }[];
  target: { checked: boolean; name: string; }[];
}
export type MappingSide = keyof MappingColumns;

export function createMappingColumns(
  sources: MappingField[],
  targets: MappingField[],
  mappings?: FieldMapping[],
  layout?: unknown,
): MappingColumns {
  // 布局必须与执行配置一致，不能用陈旧 UI 状态改写真实映射。
  if (mappings && validLayout(layout, mappings)) {
    const columns = layout as FieldMappingLayout;
    return refreshMappingColumns({
      source: columns.source.map((field, index) => ({ ...field, key: `source:${index}:${field.name}`, exists: true })),
      target: columns.target.map((field, index) => ({ ...field, key: `target:${index}:${field.name}`, exists: true })),
    }, sources, targets);
  }
  const sourceByName = new Map(sources.map((field) => [field.name, field]));
  const targetByName = new Map(targets.map((field) => [field.name, field]));
  const sourceNames = mappings?.length ? mappings.map((item) => item.source).filter(Boolean) : sources.map((item) => item.name);
  for (const source of sources) if (!sourceNames.includes(source.name)) sourceNames.push(source.name);
  const used = new Set<string>();
  const source = sourceNames.map((name, index) => ({
    ...sourceByName.get(name), name, key: `source:${index}:${name}`,
    checked: mappings === undefined ? targetByName.has(name) : !!mappings[index]?.enabled,
    exists: sourceByName.has(name) || sources.length === 0,
  }));
  const target: MappingColumn[] = [];
  for (let index = 0; index < source.length; index++) {
    const configured = mappings?.[index];
    let name: string;
    if (mappings === undefined) name = targetByName.has(source[index]!.name) ? source[index]!.name : '';
    else name = configured?.target ?? targets.find((field) => !used.has(field.name))?.name ?? '';
    const exists = targetByName.has(name) && !used.has(name);
    if (name) used.add(name);
    // 已保存的勾选表示用户意图；元数据尚未返回不能把它改成未勾选。
    target.push({ ...targetByName.get(name), name, key: `target:${index}:${name}`,
      exists, checked: mappings === undefined ? exists : !!configured?.enabled });
  }
  for (const field of targets) if (!used.has(field.name)) {
    target.push({ ...field, key: `target:${target.length}:${field.name}`, exists: true, checked: false }); used.add(field.name);
  }
  return { source, target };
}

/** 元数据刷新只更新事实，不让旧响应或字段提示重置用户刚调整的顺序/勾选。 */
export function refreshMappingColumns(columns: MappingColumns, sources: MappingField[], targets: MappingField[]): MappingColumns {
  const refresh = (side: MappingSide, fields: MappingField[]) => {
    const byName = new Map(fields.map((field) => [field.name, field])); const seen = new Set<string>();
    const result: MappingColumn[] = columns[side].map((column) => {
      const exists = byName.has(column.name) && (side === 'source' || !seen.has(column.name));
      seen.add(column.name);
      return { ...column, dataType: byName.get(column.name)?.dataType,
        exists: side === 'source' && fields.length === 0 ? column.exists : exists };
    });
    for (const field of fields) if (!seen.has(field.name)) {
      result.push({ ...field, key: `${side}:${result.length}:${field.name}`, exists: true, checked: false }); seen.add(field.name);
    }
    return result;
  };
  return { source: refresh('source', sources), target: refresh('target', targets) };
}

export function serializeMappingColumns(columns: MappingColumns): FieldMapping[] {
  return columns.source.map((source, index) => {
    const target = columns.target[index];
    return { source: source.name, target: target?.name ?? '',
      enabled: !!(source.checked && source.exists && target?.checked && target.exists && source.name && target.name) };
  });
}

export function serializeMappingLayout(columns: MappingColumns): FieldMappingLayout {
  const serialize = (side: MappingSide) => columns[side].map(({ name, checked }) => ({ name, checked }));
  return { version: 1, source: serialize('source'), target: serialize('target') };
}

function validLayout(value: unknown, mappings: FieldMapping[]): value is FieldMappingLayout {
  if (!value || typeof value !== 'object') return false;
  const layout = value as FieldMappingLayout;
  if (layout.version !== 1 || !Array.isArray(layout.source) || !Array.isArray(layout.target)
    || layout.source.length !== mappings.length || layout.source.length > 2000 || layout.target.length > 2000) return false;
  if (![...layout.source, ...layout.target].every((field) => field && typeof field.name === 'string' && typeof field.checked === 'boolean')) return false;
  return mappings.every((mapping, index) => {
    const source = layout.source[index]!; const target = layout.target[index];
    return mapping.source === source.name && mapping.target === (target?.name ?? '')
      && !!mapping.enabled === !!(source.checked && target?.checked && source.name && target.name);
  });
}

export function moveMappingColumn(columns: MappingColumns, side: MappingSide, from: number, to: number): MappingColumns {
  if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || to < 0 || from >= columns[side].length || to >= columns[side].length || from === to) return columns;
  const items = [...columns[side]]; const moved = items.splice(from, 1)[0]!; items.splice(to, 0, moved);
  return { ...columns, [side]: items };
}

/** 与后端一致：启用映射必须存在，且目标列不能重复；不伪造数据库类型转换兼容性。 */
export function validateFieldMappings(mappings: FieldMapping[], sources: MappingField[], targets: MappingField[]): string[] {
  const issues: string[] = []; const sourceNames = new Set(sources.map((field) => field.name));
  const targetNames = new Set(targets.map((field) => field.name)); const seen = new Set<string>();
  const active = mappings.filter((mapping) => mapping.enabled);
  if (active.length === 0) return ['请至少配置一个字段映射'];
  for (const mapping of active) {
    if (!mapping.source || sourceNames.size > 0 && !sourceNames.has(mapping.source)) issues.push(`来源字段不存在：${mapping.source || '未选择'}`);
    if (!mapping.target || !targetNames.has(mapping.target)) issues.push(`目标字段不存在：${mapping.target || '未选择'}`);
    else if (seen.has(mapping.target)) issues.push(`目标字段不能重复：${mapping.target}`);
    seen.add(mapping.target);
  }
  return [...new Set(issues)];
}
