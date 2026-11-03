export function encodeQueryValue(value: unknown): string {
  return encodeURIComponent(value == null ? '' : String(value));
}

export function buildQueryString(params: Record<string, unknown>): string {
  return Object.entries(params)
    .map(
      ([key, value]) => `${encodeQueryValue(key)}=${encodeQueryValue(value)}`,
    )
    .join('&');
}
