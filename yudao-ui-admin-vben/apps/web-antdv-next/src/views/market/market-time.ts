import dayjs from 'dayjs';

export function formatMarketTime(value?: null | number | string) {
  if (value == null || value === '') {
    return '-';
  }
  const millis =
    typeof value === 'number'
      ? value
      : /^\d+$/.test(value)
        ? Number(value)
        : Date.parse(value);
  if (!Number.isFinite(millis)) {
    return String(value);
  }
  return dayjs(millis).format('YYYY-MM-DD HH:mm:ss');
}
