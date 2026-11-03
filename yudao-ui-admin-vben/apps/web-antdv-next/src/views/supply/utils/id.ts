export type SupplyId = number | string;

export function toIdString(id: SupplyId | null | undefined): string {
  if (id === undefined || id === null || id === '') {
    return '';
  }
  return String(id);
}

export function sameId(
  left: SupplyId | null | undefined,
  right: SupplyId | null | undefined,
): boolean {
  const a = toIdString(left);
  const b = toIdString(right);
  return a !== '' && a === b;
}
