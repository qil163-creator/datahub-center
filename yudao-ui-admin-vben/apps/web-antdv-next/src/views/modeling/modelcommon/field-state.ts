import type { ModelingModelApi } from '#/api/modeling/model';

let fallbackUidSequence = 0;

export function generateFieldUid() {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }
  if (typeof cryptoApi?.getRandomValues === 'function') {
    const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6]! & 0x0F) | 0x40;
    bytes[8] = (bytes[8]! & 0x3F) | 0x80;
    const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0'));
    return [
      hex.slice(0, 4).join(''),
      hex.slice(4, 6).join(''),
      hex.slice(6, 8).join(''),
      hex.slice(8, 10).join(''),
      hex.slice(10).join(''),
    ].join('-');
  }
  fallbackUidSequence += 1;
  return [
    'field',
    Date.now().toString(36),
    fallbackUidSequence.toString(36),
    Math.random().toString(36).slice(2, 10),
  ].join('-');
}

export function ensureStableFieldUids<T extends { fieldUid?: string }>(
  fields: T[],
  createUid: () => string = generateFieldUid,
) {
  const used = new Set<string>();
  for (const field of fields) {
    let uid = field.fieldUid?.trim();
    while (!uid || used.has(uid)) {
      uid = createUid();
    }
    field.fieldUid = uid;
    used.add(uid);
  }
  return fields;
}

export function isDecimalField(field: ModelingModelApi.ModelField) {
  return /^(decimal|numeric|number|decimalv3)(\s*\(|$)/i.test(
    field.dataType?.trim() ?? '',
  );
}

/** 旧契约的长度/精度不自动猜测，必须由用户在修订版中确认后写入明确的小数位数。 */
export function needsDecimalConfirmation(field: ModelingModelApi.ModelField) {
  return (
    isDecimalField(field) &&
    (field.dataPrecision === null ||
      field.dataPrecision === undefined ||
      field.dataPrecision <= 0 ||
      field.dataScale === null ||
      field.dataScale === undefined ||
      field.dataScale < 0 ||
      field.dataScale > field.dataPrecision)
  );
}
