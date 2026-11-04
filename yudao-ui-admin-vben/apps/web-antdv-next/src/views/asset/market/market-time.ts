import dayjs from 'dayjs';

export function formatMarketTime(value?: null | number | string) {
  if (value === undefined || value === null || value === '') {
    return '-';
  }
  let millis: number;
  if (typeof value === 'number') millis = value;
  else if (/^\d+$/.test(value)) millis = Number(value);
  else millis = Date.parse(value);
  if (!Number.isFinite(millis)) {
    return String(value);
  }
  return dayjs(millis).format('YYYY-MM-DD HH:mm:ss');
}
