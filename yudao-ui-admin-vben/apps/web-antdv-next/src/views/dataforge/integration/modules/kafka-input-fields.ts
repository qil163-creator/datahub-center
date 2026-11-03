import type { ReferenceField } from './reference-node-schema';

export const kafkaInputFieldTypes = [
  'STRING',
  'BOOL',
  'BYTES',
  'DATE',
  'DOUBLE',
  'LONG',
].map((value) => ({ label: value, value }));

export function validateKafkaInputPaths(
  fields: readonly ReferenceField[],
  requirePath = false,
) {
  return fields.flatMap((field, index) => {
    const key = field.key ?? field.jsonPath;
    if (requirePath && !key?.trim())
      return [`第${index + 1}行JSON解析值不能为空`];
    if (key !== undefined && key.length > 256)
      return [`第${index + 1}行JSON解析值不能超过256个字符`];
    return [];
  });
}
