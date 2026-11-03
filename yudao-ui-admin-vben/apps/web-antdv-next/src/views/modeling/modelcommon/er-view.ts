import type { LayerNavigationItem } from './layer-navigation';

import type { ModelingModelApi } from '#/api/modeling/model';

import { isDecimalField } from './field-state';
import { prepareLayerNavigation } from './layer-navigation';

/** ER 页仅允许选择实际分层；URL 中无效或不可选的 ID 回退到首个分层。 */
export function prepareErLayers<T extends LayerNavigationItem>(
  items: readonly T[],
  requestedId: unknown,
) {
  const options = prepareLayerNavigation(items).map((item) => ({
    ...item,
    name: item.layerCode
      ? `${item.layerCode} ${item.layerName ?? ''}`.trim()
      : (item.layerName ?? ''),
  }));
  const selectable = options.filter((item) => item.nodeType === 'LAYER');
  const requested =
    typeof requestedId === 'string' || typeof requestedId === 'number'
      ? String(requestedId)
      : undefined;
  const selected =
    selectable.find((item) => item.id === requested) ?? selectable[0];
  return { options, layerId: selected?.id };
}

/** 精度和小数位使用各自字段；旧版未明确 scale 时不猜测。 */
export function formatErFieldType(field: ModelingModelApi.ModelField) {
  const type = field.dataType?.trim() ?? '';
  if (!type || type.includes('(')) return type;
  if (isDecimalField(field)) {
    const precision = field.dataPrecision;
    const scale = field.dataScale;
    return precision !== null &&
      precision !== undefined &&
      precision > 0 &&
      scale !== null &&
      scale !== undefined &&
      scale >= 0 &&
      scale <= precision
      ? `${type}(${precision},${scale})`
      : type;
  }
  return field.dataLength !== null &&
    field.dataLength !== undefined &&
    field.dataLength > 0
    ? `${type}(${field.dataLength})`
    : type;
}
