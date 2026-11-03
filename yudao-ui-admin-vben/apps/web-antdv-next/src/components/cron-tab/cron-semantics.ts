import type { CronData, CronItem, CronValue } from './types';

import { CronDataDefault, CronValueDefault } from './types';

export type WeekMode = 'quartz' | 'spring';

const springWeekdays = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];

/** Spring accepts named weekdays and 0/7 for Sunday; never shift numeric input. */
export function normalizeWeekField(value: string, mode: WeekMode): string {
  if (mode !== 'spring') return value;
  return value
    .toUpperCase()
    .replaceAll(/SUN|MON|TUE|WED|THU|FRI|SAT/g, (name) =>
      String(springWeekdays.indexOf(name)),
    );
}

export function createCronValue(mode: WeekMode): CronValue {
  const value: CronValue = JSON.parse(JSON.stringify(CronValueDefault));
  value.week.loop.start = 1;
  if (mode === 'spring') {
    value.week.range = { start: '1', end: '2' };
    value.week.loop = { start: 1, end: '1' };
    value.week.last = '1';
  }
  return value;
}

export function createCronData(mode: WeekMode): CronData {
  const data: CronData = JSON.parse(JSON.stringify(CronDataDefault));
  data.second = Array.from({ length: 60 }, (_, index) => String(index));
  data.minute = [...data.second];
  if (mode === 'spring') {
    data.week = [
      '周日',
      '周一',
      '周二',
      '周三',
      '周四',
      '周五',
      '周六',
      '周日（7）',
    ].map((label, index) => ({ label, value: String(index) }));
    data.year = Array.from({ length: 130 }, (_, index) => 1970 + index);
  }
  return data;
}

export function sundayShortcut(mode: WeekMode): string {
  return `0 0 0 ? * ${mode === 'spring' ? '0' : '1'}`;
}

export function parseYearInterval(value: string): {
  end: number;
  start: number;
} {
  const [start, end] = value.split('/');
  return { start: start === '*' ? 1970 : Number(start), end: Number(end) };
}

export function weekExpression(value: CronItem): string {
  switch (value.type) {
    case '1': {
      return `${value.range.start}-${value.range.end}`;
    }
    case '2': {
      return `${value.loop.end}#${value.loop.start}`;
    }
    case '3': {
      return value.appoint.length > 0 ? value.appoint.join(',') : '*';
    }
    case '4': {
      return `${value.last}L`;
    }
    case '5': {
      return '?';
    }
    case '6': {
      return `${value.loop.start}/${value.loop.end}`;
    }
    case '7': {
      return value.appoint[0] || '*';
    }
    default: {
      return '*';
    }
  }
}
