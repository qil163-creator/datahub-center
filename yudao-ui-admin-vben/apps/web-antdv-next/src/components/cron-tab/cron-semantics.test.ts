import { describe, expect, it } from 'vitest';

import {
  createCronData,
  createCronValue,
  normalizeWeekField,
  parseYearInterval,
  sundayShortcut,
  weekExpression,
} from './cron-semantics';

describe('data development Spring cron semantics', () => {
  it('keeps Sunday shortcuts and weekday options aligned while preserving Quartz callers', () => {
    expect(sundayShortcut('spring')).toBe('0 0 0 ? * 0');
    expect(sundayShortcut('quartz')).toBe('0 0 0 ? * 1');
    expect(
      createCronData('spring').week.find((item) => item.label === '周一')
        ?.value,
    ).toBe('1');
    expect(
      createCronData('quartz').week.find((item) => item.label === '周一')
        ?.value,
    ).toBe('2');
    expect(normalizeWeekField('1-7', 'spring')).toBe('1-7');
    expect(normalizeWeekField('MON-FRI', 'spring')).toBe('1-5');
    expect(normalizeWeekField('SUN,MON', 'spring')).toBe('0,1');
    expect(normalizeWeekField('FRIL', 'spring')).toBe('5L');
    expect(normalizeWeekField('TUE#2', 'spring')).toBe('2#2');
    expect(normalizeWeekField('MON-FRI/2', 'spring')).toBe('1-5/2');
  });

  it('uses unchanged Spring numbers for specified/range/interval/nth/last weekdays', () => {
    const value = createCronValue('spring').week;
    value.type = '3';
    value.appoint = ['0', '1', '6'];
    expect(weekExpression(value)).toBe('0,1,6');
    value.type = '1';
    value.range = { start: '1', end: '7' };
    expect(weekExpression(value)).toBe('1-7');
    value.type = '6';
    value.loop = { start: '1', end: 2 };
    expect(weekExpression(value)).toBe('1/2');
    value.type = '2';
    value.loop = { start: 2, end: '1' };
    expect(weekExpression(value)).toBe('1#2');
    value.type = '4';
    value.last = '1';
    expect(weekExpression(value)).toBe('1L');
  });

  it('keeps independent component state and does not reverse year intervals', () => {
    const first = createCronValue('spring');
    first.week.last = '5';
    expect(createCronValue('spring').week.last).toBe('1');
    expect(parseYearInterval('2026/2')).toEqual({ start: 2026, end: 2 });
    expect(parseYearInterval('*/4')).toEqual({ start: 1970, end: 4 });
    const years = createCronData('spring').year;
    expect(years[0]).toBe(1970);
    expect(years.at(-1)).toBe(2099);
  });
});
