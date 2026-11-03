import type { DataAssetApi } from '#/api/asset/dataasset';

import { formatDateTime } from '@vben/utils';

/** 数仓分层 -> 表类型展示（贴源层为明细表、汇总层为汇总表等） */
export const TABLE_KIND_META: Record<string, { color: string; label: string }> =
  {
    ODS: { label: '贴源逻辑表', color: 'cyan' },
    DWD: { label: '事实逻辑表', color: 'cyan' },
    DIM: { label: '维度逻辑表', color: 'purple' },
    DWS: { label: '汇总逻辑表', color: 'geekblue' },
    DWM: { label: '汇总表', color: 'geekblue' },
    ADS: { label: 'ADS应用表', color: 'magenta' },
    APP: { label: '应用表', color: 'magenta' },
  };

/** 预览过滤操作符 */
export const PREVIEW_OPERATOR_OPTIONS = [
  { label: '等于', value: 'eq' },
  { label: '不等于', value: 'ne' },
  { label: '大于', value: 'gt' },
  { label: '大于等于', value: 'ge' },
  { label: '小于', value: 'lt' },
  { label: '小于等于', value: 'le' },
  { label: '包含', value: 'like' },
  { label: '为空', value: 'isNull' },
  { label: '不为空', value: 'notNull' },
];

export const NO_VALUE_OPERATORS = new Set(['isNull', 'notNull']);

/** 质量任务运行状态 */
export const RUN_STATUS_OPTIONS = [
  { label: '未运行', value: 0, color: 'default' },
  { label: '运行中', value: 1, color: 'processing' },
  { label: '成功', value: 2, color: 'success' },
  { label: '失败', value: 3, color: 'error' },
];

export const SCHEDULE_TYPE_OPTIONS = [
  { label: '手动执行', value: 0 },
  { label: '定时调度', value: 1 },
];

export function formatSize(bytes?: number) {
  if (bytes === undefined || bytes === null) return '-';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

export function formatCount(value?: number) {
  return value === undefined || value === null
    ? '-'
    : new Intl.NumberFormat('zh-CN').format(value);
}

/** 字段中文名：业务名称 > 自定义注释 > 源注释 */
export function columnDisplayName(column: DataAssetApi.MetadataColumn) {
  return (
    column.businessName || column.customComment || column.sourceComment || '-'
  );
}

export function optionLabel<T extends { label: string; value: unknown }>(
  options: T[],
  value: unknown,
) {
  return options.find((item) => item.value === value)?.label ?? '-';
}

export function optionColor<T extends { color?: string; value: unknown }>(
  options: T[],
  value: unknown,
) {
  return options.find((item) => item.value === value)?.color ?? 'default';
}

const DATETIME_TYPE_RE =
  /timestamp|datetime|timestamptz|smalldatetime|datetimeoffset/i;
const DATE_TYPE_RE = /\bdate\b/i;
const DATETIME_VALUE_RE =
  /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})?$/;

function isDateTimeColumnType(typeName?: string) {
  if (!typeName) return false;
  const type = typeName.toLowerCase();
  return DATETIME_TYPE_RE.test(type) || DATE_TYPE_RE.test(type);
}

/** 查询 / 预览单元格：日期时间列统一展示为 YYYY-MM-DD HH:mm:ss */
export function formatQueryCell(value: unknown, typeName?: string): string {
  if (value === undefined || value === null || value === '') return '';
  if (typeof value === 'object') return JSON.stringify(value);
  const text = String(value);
  const shouldFormat =
    isDateTimeColumnType(typeName) || DATETIME_VALUE_RE.test(text.trim());
  if (!shouldFormat) return text;
  return formatDateTime(value as number | string) || text;
}
