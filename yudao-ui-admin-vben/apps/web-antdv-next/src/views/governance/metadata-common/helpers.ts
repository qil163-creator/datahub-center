import { formatDate } from '@vben/utils';

export type MetadataId = number | string;

export function metadataTime(value?: null | number | string, format?: string) {
  return value === undefined || value === null || value === ''
    ? '-'
    : formatDate(value, format ?? 'YYYY-MM-DD HH:mm:ss');
}

export function metadataSize(value?: null | number | string) {
  if (value === undefined || value === null) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = Number(value);
  if (!Number.isFinite(size) || size < 0) return '-';
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index++;
  }
  return `${size.toFixed(2)} ${units[index]}`;
}

export const collectTypeLabel = (value?: number) =>
  value === 2 ? '非结构化数据' : '数据库表';

/** 将固定时刻的 Quartz 周期显示为列表中的自然语言，复杂表达式保留原文。 */
export function metadataSchedule(cron?: string) {
  if (!cron) return '-';
  const [second, minute, hour, day, month, weekday] = cron.trim().split(/\s+/);
  if (
    second !== '0' ||
    !/^\d{1,2}$/.test(minute ?? '') ||
    !/^\d{1,2}$/.test(hour ?? '') ||
    month !== '*'
  )
    return cron;
  const time = `${hour!.padStart(2, '0')}:${minute!.padStart(2, '0')}`;
  if (day === '*' && (weekday === '?' || weekday === '*'))
    return `在${time} 执行`;
  if (/^\d{1,2}$/.test(day ?? '') && weekday === '?')
    return `在${time}，限每月 ${day} 号 执行`;
  return cron;
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请重试';
}
