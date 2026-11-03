import { describe, expect, it } from 'vitest';

import { getCronPreview } from './cron-preview';

const reference = new Date(2026, 9, 11, 22, 43, 17, 123);

function localTime(date: Date): string {
  const parts = [
    date.getFullYear(),
    date.getMonth() + 1,
    date.getDate(),
    date.getHours(),
    date.getMinutes(),
    date.getSeconds(),
  ].map((value) => String(value).padStart(2, '0'));
  return `${parts.slice(0, 3).join('-')} ${parts.slice(3).join(':')}`;
}

describe('运行时间预览', () => {
  it('计算严格晚于起点的五次运行时间，不修改起始日期', () => {
    const before = reference.getTime();
    expect(
      getCronPreview('0 * * * * ?', 'quartz', reference).map((date) =>
        localTime(date),
      ),
    ).toEqual([
      '2026-10-11 22:44:00',
      '2026-10-11 22:45:00',
      '2026-10-11 22:46:00',
      '2026-10-11 22:47:00',
      '2026-10-11 22:48:00',
    ]);
    expect(reference.getTime()).toBe(before);
  });

  it('按显式指定的上海时区计算，返回对应的真实时间戳', () => {
    expect(
      getCronPreview(
        '0 0 9 * * ?',
        'spring',
        new Date('2026-10-11T14:43:17.123Z'),
        'Asia/Shanghai',
      ).map((date) => date.toISOString()),
    ).toEqual([
      '2026-10-12T01:00:00.000Z',
      '2026-10-13T01:00:00.000Z',
      '2026-10-14T01:00:00.000Z',
      '2026-10-15T01:00:00.000Z',
      '2026-10-16T01:00:00.000Z',
    ]);
  });

  it('指定时区进入夏令时后仍保持当地上午九点的规则', () => {
    expect(
      getCronPreview(
        '0 0 9 * * ?',
        'spring',
        new Date('2026-03-07T12:00:00Z'),
        'America/New_York',
      ).map((date) => date.toISOString()),
    ).toEqual([
      '2026-03-07T14:00:00.000Z',
      '2026-03-08T13:00:00.000Z',
      '2026-03-09T13:00:00.000Z',
      '2026-03-10T13:00:00.000Z',
      '2026-03-11T13:00:00.000Z',
    ]);
  });

  it('跳过夏令时开始日不存在的凌晨两点半，并补足五次匹配时间', () => {
    expect(
      getCronPreview(
        '0 30 2 * * ?',
        'spring',
        new Date('2026-03-07T12:00:00Z'),
        'America/New_York',
      ).map((date) => date.toISOString()),
    ).toEqual([
      '2026-03-09T06:30:00.000Z',
      '2026-03-10T06:30:00.000Z',
      '2026-03-11T06:30:00.000Z',
      '2026-03-12T06:30:00.000Z',
      '2026-03-13T06:30:00.000Z',
    ]);
  });

  it('保留数字起点步长、范围与指定列表的秒级语义', () => {
    expect(
      getCronPreview('10/15 * * * * ?', 'spring', reference).map((date) =>
        localTime(date),
      ),
    ).toEqual([
      '2026-10-11 22:43:25',
      '2026-10-11 22:43:40',
      '2026-10-11 22:43:55',
      '2026-10-11 22:44:10',
      '2026-10-11 22:44:25',
    ]);
    expect(
      getCronPreview('20-22 * * * * ?', 'spring', reference).map((date) =>
        localTime(date),
      ),
    ).toEqual([
      '2026-10-11 22:43:20',
      '2026-10-11 22:43:21',
      '2026-10-11 22:43:22',
      '2026-10-11 22:44:20',
      '2026-10-11 22:44:21',
    ]);
    expect(
      getCronPreview('0,30 * * * * ?', 'spring', reference).map((date) =>
        localTime(date),
      ),
    ).toEqual([
      '2026-10-11 22:43:30',
      '2026-10-11 22:44:00',
      '2026-10-11 22:44:30',
      '2026-10-11 22:45:00',
      '2026-10-11 22:45:30',
    ]);
  });

  it('计算真实月末和闰日，不把日期规则简化为每天运行', () => {
    expect(
      getCronPreview('0 0 0 L * ?', 'quartz', reference).map((date) =>
        localTime(date),
      ),
    ).toEqual([
      '2026-10-31 00:00:00',
      '2026-11-30 00:00:00',
      '2026-12-31 00:00:00',
      '2027-01-31 00:00:00',
      '2027-02-28 00:00:00',
    ]);
    expect(
      getCronPreview('0 0 0 29 FEB ? 2027-2032', 'spring', reference).map(
        (date) => localTime(date),
      ),
    ).toEqual(['2028-02-29 00:00:00', '2032-02-29 00:00:00']);
  });

  it('分别使用 Quartz 和 Spring 的星期编号计算周日与第几个星期', () => {
    const quartz = getCronPreview('0 0 0 ? * 1', 'quartz', reference);
    const spring = getCronPreview('0 0 0 ? * 0', 'spring', reference);
    expect(quartz).toEqual(spring);
    expect(quartz.every((date) => date.getDay() === 0)).toBe(true);
    expect(getCronPreview('0 0 0 ? * 7', 'spring', reference)).toEqual(spring);
    expect(
      getCronPreview('0 0 0 ? * 2#2', 'quartz', reference).map((date) =>
        localTime(date),
      ),
    ).toEqual([
      '2026-10-12 00:00:00',
      '2026-11-09 00:00:00',
      '2026-12-14 00:00:00',
      '2027-01-11 00:00:00',
      '2027-02-08 00:00:00',
    ]);
    expect(getCronPreview('0 0 0 ? * 1#2', 'spring', reference)).toEqual(
      getCronPreview('0 0 0 ? * MON#2', 'spring', reference),
    );
  });

  it('保持最后一个星期和最近工作日的日历含义', () => {
    expect(
      getCronPreview('0 0 0 ? * 2L', 'quartz', reference).map((date) =>
        localTime(date),
      ),
    ).toEqual([
      '2026-10-26 00:00:00',
      '2026-11-30 00:00:00',
      '2026-12-28 00:00:00',
      '2027-01-25 00:00:00',
      '2027-02-22 00:00:00',
    ]);
    expect(getCronPreview('0 0 0 ? * MONL', 'spring', reference)).toEqual(
      getCronPreview('0 0 0 ? * 2L', 'quartz', reference),
    );
    expect(getCronPreview('0 0 0 1W * ?', 'spring', reference)[0]).toEqual(
      new Date(2026, 10, 2),
    );
  });

  it('对 Spring 周步长保留通配起点和 0/7 周日别名', () => {
    expect(
      getCronPreview('0 0 0 ? * */2', 'spring', reference).map((date) =>
        localTime(date),
      ),
    ).toEqual([
      '2026-10-12 00:00:00',
      '2026-10-14 00:00:00',
      '2026-10-16 00:00:00',
      '2026-10-18 00:00:00',
      '2026-10-19 00:00:00',
    ]);
    expect(
      getCronPreview('0 0 0 ? * 7/2', 'spring', reference).every(
        (date) => date.getDay() === 0,
      ),
    ).toBe(true);
    expect(
      getCronPreview('0 0 0 ? * 0/2', 'spring', reference).map((date) =>
        date.getDay(),
      ),
    ).toEqual([2, 4, 6, 0, 2]);
  });

  it('年通配步长从 1970 起算，数字步长及范围均止于 2099', () => {
    const annual = '0 0 0 1 JAN ? ';
    expect(
      getCronPreview(`${annual}*/2`, 'spring', reference).map((date) =>
        date.getFullYear(),
      ),
    ).toEqual([2028, 2030, 2032, 2034, 2036]);
    expect(
      getCronPreview(`${annual}2027/3`, 'spring', reference).map((date) =>
        date.getFullYear(),
      ),
    ).toEqual([2027, 2030, 2033, 2036, 2039]);
    expect(
      getCronPreview(`${annual}2028-2032/2`, 'spring', reference).map((date) =>
        date.getFullYear(),
      ),
    ).toEqual([2028, 2030, 2032]);
    expect(
      getCronPreview(`${annual}2028,2030`, 'quartz', reference).map((date) =>
        date.getFullYear(),
      ),
    ).toEqual([2028, 2030]);
  });

  it('年份过期或日期不存在时返回空列表，剩余次数不足五次时如实返回', () => {
    expect(getCronPreview('0 * * * * ? 1970', 'spring', reference)).toEqual([]);
    expect(getCronPreview('0 0 0 31 2 ? 2027', 'spring', reference)).toEqual(
      [],
    );
    expect(
      getCronPreview(
        '* * * * * ? *',
        'spring',
        new Date(2099, 11, 31, 23, 59, 57),
      ).map((date) => localTime(date)),
    ).toEqual(['2099-12-31 23:59:58', '2099-12-31 23:59:59']);
  });

  it('日和周同时限制时采用 Spring 交集，不产生额外运行日期', () => {
    expect(getCronPreview('0 0 0 13 * MON', 'spring', reference)[0]).toEqual(
      new Date(2027, 8, 13),
    );
  });

  it.each([
    '',
    '0 0 * * *',
    '0 0 0 * * ? 2026 2027',
    '60 * * * * ?',
    '0 0 24 * * ?',
    '0 0 0 0 * ?',
    '0 0 0 * 13 ?',
    '0/0 * * * * ?',
    '0/1.5 * * * * ?',
    '10-5 * * * * ?',
    '0,,30 * * * * ?',
    '0 0 0 L-2 * ?',
    '0 0 0 L,15 * ?',
    '0 0 0 ? * MON#6',
    '0 0 0 ? * +MON',
    '0 0 0 ? * 8',
    '0 0 0 * * ? 1969',
    '0 0 0 * * ? 2100',
    '0 0 0 * * ? 2028-2026',
    '0 0 0 * * ? */131',
    '0 0 0 * * ? 2027,',
    '? * * * * ?',
    '0 * * * * ?'.repeat(30),
  ])('拒绝无效或未支持的表达式 %s', (expression) => {
    expect(() => getCronPreview(expression, 'spring', reference)).toThrow(
      /表达式|规则/,
    );
  });

  it('拒绝 Quartz 日和周同时指定以及无效起始日期', () => {
    expect(() => getCronPreview('0 0 0 13 * 2', 'quartz', reference)).toThrow(
      /表达式|规则/,
    );
    expect(() =>
      getCronPreview('0 * * * * ?', 'spring', new Date(Number.NaN)),
    ).toThrow('预览起始时间无效');
  });
});
