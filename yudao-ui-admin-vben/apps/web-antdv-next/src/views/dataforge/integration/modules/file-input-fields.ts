import type { ReferenceField } from './reference-node-schema';

export const fileInputFieldTypes = [
  'long',
  'boolean',
  'string',
  'date',
  'double',
].map((value) => ({ label: value, value }));

export const fileInputDateFormats = ['yyyy-MM-dd', 'yyyy-MM-dd HH:mm:ss'].map(
  (value) => ({ label: value, value }),
);

export function isFileInputDate(field: ReferenceField) {
  return /^(?:date|datetime|timestamp|localdate|localdatetime)$/iu.test(
    field.type ?? '',
  );
}

export function validateFileInputDates(fields: readonly ReferenceField[]) {
  return fields.flatMap((field, index) =>
    isFileInputDate(field) && !field.format?.trim()
      ? [`第${index + 1}行日期格式不能为空`]
      : [],
  );
}

export function safeFileDownloadUrl(value: unknown, base: string) {
  if (typeof value !== 'string' || !value.trim()) return undefined;
  try {
    const url = new URL(value, base);
    return ['http:', 'https:'].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : undefined;
  } catch {
    return undefined;
  }
}
