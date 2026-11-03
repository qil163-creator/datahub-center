import type { TableModelApi } from '#/api/modeling/tablemodel';

/** 头信息和字段设计必须属于同一模型、同一修订，避免跨请求拼接旧设计。 */
export function assertCurrentDesign(
  model: TableModelApi.Model,
  design: TableModelApi.Design,
) {
  if (
    model.id === undefined ||
    model.id === null ||
    design.modelId === undefined ||
    design.modelId === null ||
    model.revision === undefined ||
    model.revision === null ||
    design.revision === undefined ||
    design.revision === null ||
    String(model.id) !== String(design.modelId) ||
    String(model.revision) !== String(design.revision)
  ) {
    throw new Error('模型设计已变化，请刷新后重新编辑');
  }
}

/** 以稳定键顺序比较本地字段和配置，保留未保存修改提示。 */
export function stableDesignValue(value: unknown): string {
  if (value === undefined) return '（无）';
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value))
    return `[${value.map((item) => stableDesignValue(item)).join(',')}]`;
  return `{${Object.entries(value)
    .toSorted(([a], [b]) => a.localeCompare(b))
    .map(([key, item]) => `${JSON.stringify(key)}:${stableDesignValue(item)}`)
    .join(',')}}`;
}
