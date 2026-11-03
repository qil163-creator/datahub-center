import dayjs from 'dayjs';

const DATE_TIME_FORMAT = 'YYYY-MM-DD HH:mm:ss';

/**
 * 数据服务时间展示：兼容毫秒/秒时间戳、数字字符串和日期字符串。
 * 不用 dayjs.tz()，避免时区未初始化时回退成原始时间戳。
 */
export function formatSupplyDateTime(value?: Date | null | number | string) {
  if (value === undefined || value === null || value === '') {
    return '';
  }
  let numeric = Number.NaN;
  if (typeof value === 'number') {
    numeric = value;
  } else if (typeof value === 'string' && /^\d+$/.test(value.trim())) {
    numeric = Number(value);
  }
  let millis: number | undefined;
  if (Number.isFinite(numeric)) {
    millis = numeric < 1e12 ? numeric * 1000 : numeric;
  }
  const date = millis === undefined ? dayjs(value) : dayjs(millis);
  return date.isValid() ? date.format(DATE_TIME_FORMAT) : '';
}

/** 仅日期（兼容历史 LocalDate 字段） */
export function formatSupplyDate(value?: Date | null | number | string) {
  const dateTime = formatSupplyDateTime(value);
  return dateTime ? dateTime.slice(0, 10) : '';
}

/** 区间展示，两端都按 YYYY-MM-DD HH:mm:ss */
export function formatSupplyDateTimeRange(
  start?: Date | null | number | string,
  end?: Date | null | number | string,
) {
  const from = formatSupplyDateTime(start);
  const to = formatSupplyDateTime(end);
  if (!from && !to) {
    return '';
  }
  return `${from || '-'} ~ ${to || '-'}`;
}

/** 提交给后端 LocalDateTime（Jackson 默认毫秒时间戳） */
export function toSupplyDateTimeMillis(value?: Date | null | number | string) {
  const formatted = formatSupplyDateTime(value);
  if (!formatted) {
    return undefined;
  }
  const millis = dayjs(formatted, DATE_TIME_FORMAT).valueOf();
  return Number.isFinite(millis) ? millis : undefined;
}
