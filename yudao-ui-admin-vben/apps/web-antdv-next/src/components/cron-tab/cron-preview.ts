import type { WeekMode } from './cron-semantics';

import { Cron } from 'croner';

const MIN_YEAR = 1970;
const MAX_YEAR = 2099;
const TIMEZONE_ERROR = '当前时区无法稳定计算运行时间，请检查时区和 Cron 规则。';
const MONTH_NAMES = [
  'JAN',
  'FEB',
  'MAR',
  'APR',
  'MAY',
  'JUN',
  'JUL',
  'AUG',
  'SEP',
  'OCT',
  'NOV',
  'DEC',
];
const WEEK_NAMES = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];

function invalid(label = 'Cron'): Error {
  return new Error(`${label}表达式无效或暂不支持预览，请检查规则。`);
}

function normalizeField(
  field: string,
  min: number,
  max: number,
  label: string,
  springWeek = false,
): string {
  return field
    .split(',')
    .map((segment) => {
      if (segment === '*') return `${min}-${max}`;
      const match = /^(\d{1,4}|\*)(?:-(\d{1,4}))?(?:\/(\d{1,4}))?$/.exec(
        segment,
      );
      if (!match || (match[1] === '*' && match[2])) throw invalid(label);

      const start = match[1] === '*' ? min : Number(match[1]);
      const hasRange = match[2] !== undefined;
      const hasStep = match[3] !== undefined;
      let end = start;
      if (hasRange) end = Number(match[2]);
      else if (hasStep) end = max;
      const step = hasStep ? Number(match[3]) : 1;
      if (
        start < min ||
        start > max ||
        end < min ||
        end > max ||
        step < 1 ||
        step > max - min + 1
      ) {
        throw invalid(label);
      }

      // Spring 的星期范围把起点 7 视为 0；指定值和数字步长仍保留 7。
      const rangeStart = springWeek && hasRange && start === 7 ? 0 : start;
      if (rangeStart > end) throw invalid(label);
      if (!hasRange && !hasStep) return String(start);
      return `${rangeStart}-${end}${hasStep ? `/${step}` : ''}`;
    })
    .join(',');
}

function normalizeDay(field: string): string {
  if (field === '?') return '*';
  if (field === 'L' || field === 'LW') return field;
  return field
    .split(',')
    .map((segment) => {
      const weekday = /^(\d{1,2})W$/.exec(segment);
      if (weekday) {
        const day = Number(weekday[1]);
        if (day < 1 || day > 31) throw invalid('日');
        return `${day}W`;
      }
      return normalizeField(segment, 1, 31, '日');
    })
    .join(',');
}

function normalizeWeek(field: string, mode: WeekMode): string {
  if (field === '?' || field === '*') return '*';
  const normalized = field.replaceAll(/SUN|MON|TUE|WED|THU|FRI|SAT/g, (name) =>
    String(WEEK_NAMES.indexOf(name) + (mode === 'quartz' ? 1 : 0)),
  );
  const min = mode === 'quartz' ? 1 : 0;
  return normalized
    .split(',')
    .map((segment) => {
      const modifier = /^(\d)(?:L|#([1-5]))$/.exec(segment);
      if (modifier) {
        const day = Number(modifier[1]);
        if (day < min || day > 7) throw invalid('周');
        return modifier[2] ? `${day}#${modifier[2]}` : `${day}L`;
      }
      // Spring 的星期通配步长以周一（1）起算，数字 0 和 7 仍分别保持原意。
      if (mode === 'spring' && /^\*\/\d+$/.test(segment)) {
        return normalizeField(segment, 1, 7, '周');
      }
      return normalizeField(segment, min, 7, '周', mode === 'spring');
    })
    .join(',');
}

/** 按指定时区计算后续五次运行时间，未指定时使用浏览器本地时区；不注册定时任务。 */
export function getCronPreview(
  expression: string,
  mode: WeekMode,
  fromDate = new Date(),
  timezone?: string,
): Date[] {
  if (!expression.trim() || expression.length > 256) throw invalid();
  if (Number.isNaN(fromDate.getTime())) throw new Error('预览起始时间无效。');
  const fields = expression.trim().toUpperCase().split(/\s+/);
  if (fields.length !== 6 && fields.length !== 7) {
    throw new Error('Cron 表达式需要包含 6 或 7 个字段。');
  }
  const [second, minute, hour, day, month, week, year] = fields;
  if (
    !second ||
    !minute ||
    !hour ||
    !day ||
    !month ||
    !week ||
    (mode === 'quartz' && (day === '?') === (week === '?'))
  ) {
    throw invalid();
  }
  const normalizedMonth = month.replaceAll(
    /JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC/g,
    (name) => String(MONTH_NAMES.indexOf(name) + 1),
  );
  const normalized = [
    normalizeField(second, 0, 59, '秒'),
    normalizeField(minute, 0, 59, '分钟'),
    normalizeField(hour, 0, 23, '小时'),
    normalizeDay(day),
    normalizeField(normalizedMonth, 1, 12, '月'),
    normalizeWeek(week, mode),
    ...(year ? [normalizeField(year, MIN_YEAR, MAX_YEAR, '年')] : []),
  ].join(' ');

  try {
    const cron = new Cron(normalized, {
      alternativeWeekdays: mode === 'quartz',
      domAndDow: true,
      mode: '6-or-7-parts',
      paused: true,
      timezone,
    });
    const nextTimes: Date[] = [];
    let cursor = fromDate;
    // 用公开匹配接口排除夏令时跳时产生的偏移候选，并限制补算次数。
    for (let attempt = 0; attempt < 15 && nextTimes.length < 5; attempt++) {
      const candidate = cron.nextRun(cursor);
      if (!candidate) return nextTimes;
      if (candidate.getTime() <= cursor.getTime()) {
        throw new Error(TIMEZONE_ERROR);
      }
      const matchesSchedule = cron.match(candidate);
      if (matchesSchedule) nextTimes.push(candidate);
      cursor = candidate;
    }
    if (nextTimes.length < 5) throw new Error(TIMEZONE_ERROR);
    return nextTimes;
  } catch (error) {
    if (error instanceof Error && error.message === TIMEZONE_ERROR) throw error;
    throw invalid();
  }
}
