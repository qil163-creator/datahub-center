import type { ConceptModelApi } from '#/api/modeling/concept-model';

export const cardinalityOptions = [
  { label: '一对一', value: 'ONE_TO_ONE' },
  { label: '一对多', value: 'ONE_TO_MANY' },
  { label: '多对多', value: 'MANY_TO_MANY' },
];
export const dataTypeOptions = [
  { label: '文本', value: 'STRING' },
  { label: '数值', value: 'NUMBER' },
  { label: '布尔', value: 'BOOLEAN' },
  { label: '日期', value: 'DATE' },
  { label: '日期时间', value: 'DATETIME' },
];

export function emptyDesign(): ConceptModelApi.Design {
  return { entities: [], relations: [] };
}

export function designKey(prefix: string) {
  return `${prefix}_${crypto.randomUUID()}`;
}

/** 删除实体时同时移除相关边，保留其他实体及其稳定标识。 */
export function removeDesignEntity(
  design: ConceptModelApi.Design,
  key: string,
) {
  design.entities = design.entities.filter((entity) => entity.key !== key);
  design.relations = design.relations.filter(
    (relation) =>
      relation.sourceEntityKey !== key && relation.targetEntityKey !== key,
  );
}

/** 提交前提供定位清晰的提示，服务端仍独立验证完整设计。 */
export function validateDesign(
  design: ConceptModelApi.Design,
): string | undefined {
  if (design.entities.length > 50 || design.relations.length > 100)
    return '最多支持 50 个实体和 100 条关系';
  const entityKeys = new Set<string>();
  const codes = new Set<string>();
  const validCode = /^[A-Z][\w]{0,63}$/i;
  for (const [index, entity] of design.entities.entries()) {
    const label = `实体 ${index + 1}`;
    if (!entity.name.trim()) return `${label}：请填写名称`;
    if (!validCode.test(entity.code.trim()))
      return `${label}：编码须以字母开头，仅含字母、数字或下划线，最多 64 位`;
    if (codes.has(entity.code.trim().toLowerCase()))
      return `${label}：编码重复`;
    if (entityKeys.has(entity.key)) return `${label}：实体标识重复，请重新添加`;
    codes.add(entity.code.trim().toLowerCase());
    entityKeys.add(entity.key);
    if (entity.attributes.length > 30) return `${label}：最多支持 30 个属性`;
    const attrNames = new Set<string>();
    const attrCodes = new Set<string>();
    for (const [attrIndex, attribute] of entity.attributes.entries()) {
      if (!attribute.name.trim() || !validCode.test(attribute.code.trim()))
        return `${label}的属性 ${attrIndex + 1}：请填写名称及有效编码`;
      if (
        attrNames.has(attribute.name.trim()) ||
        attrCodes.has(attribute.code.trim().toLowerCase())
      )
        return `${label}：属性名称或编码重复`;
      attrNames.add(attribute.name.trim());
      attrCodes.add(attribute.code.trim().toLowerCase());
    }
  }
  const endpoints = new Set<string>();
  for (const [index, relation] of design.relations.entries()) {
    const label = `关系 ${index + 1}`;
    if (!relation.name.trim()) return `${label}：请填写关系名称`;
    if (
      !entityKeys.has(relation.sourceEntityKey) ||
      !entityKeys.has(relation.targetEntityKey)
    )
      return `${label}：请选择有效的起点和终点实体`;
    if (relation.sourceEntityKey === relation.targetEntityKey)
      return `${label}：起点与终点不能相同`;
    const endpoint = `${relation.sourceEntityKey}:${relation.targetEntityKey}`;
    if (endpoints.has(endpoint)) return `${label}：相同方向的实体关系重复`;
    endpoints.add(endpoint);
  }
  return undefined;
}
