import { formatDate } from '@vben/utils';

/**
 * 首页看板通用工具。
 *
 * 看板数据来自多个可选数据源，约定：请求失败 → undefined（展示为 “--”），
 * 请求成功但确实没有数据 → 0 / 空数组（展示为 0 / 空态）。二者必须区分。
 */

/** 静默执行可选请求：失败返回 undefined，不向上抛出 */
export async function settle<T>(
  request: (() => Promise<T>) | Promise<T>,
): Promise<T | undefined> {
  try {
    return await (typeof request === 'function' ? request() : request);
  } catch {
    return undefined;
  }
}

/** 数值展示：undefined 显示为 “--”，其余按千分位格式化 */
export function formatCount(value?: null | number): string {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '--';
  }
  return value.toLocaleString('zh-CN');
}

/** 百分比展示：后端各统计接口统一返回 0~100 口径（元数据覆盖率 / 落标率 / API 成功率等） */
export function formatPercent(value?: null | number, digits = 1): string {
  if (value === undefined || value === null || Number.isNaN(value)) {
    return '--';
  }
  return `${Number(value).toFixed(digits)}%`;
}

/** 今日日期文案：2026年09月08日 星期二 */
export function todayText(): string {
  const now = new Date();
  const weekday = ['日', '一', '二', '三', '四', '五', '六'][now.getDay()];
  return `${formatDate(now, 'YYYY年MM月DD日')} 星期${weekday}`;
}

/** 毫秒时长转可读文案 */
export function formatDuration(ms?: null | number): string {
  if (ms === undefined || ms === null || ms < 0) {
    return '-';
  }
  if (ms < 1000) {
    return `${ms} ms`;
  }
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) {
    return `${seconds} 秒`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} 分 ${seconds % 60} 秒`;
  }
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
}
