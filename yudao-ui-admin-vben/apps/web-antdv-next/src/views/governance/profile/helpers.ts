import type { GovernanceProfileApi as Api } from '#/api/governance/profile';

/** 轮询间隔（毫秒）。 */
export const PROFILE_POLL_INTERVAL = 3000;

export const RUN_STATUS_META: Record<
  Api.RunStatus,
  { color: string; label: string }
> = {
  PENDING: { color: 'default', label: '等待执行' },
  RUNNING: { color: 'processing', label: '执行中' },
  SUCCESS: { color: 'success', label: '成功' },
  FAILED: { color: 'error', label: '失败' },
};

export const COLUMN_KIND_LABELS: Record<Api.ColumnKind, string> = {
  TEXT: '文本',
  NUMERIC: '数值',
  TEMPORAL: '日期时间',
  BOOLEAN: '布尔',
  OTHER: '其他',
};

export const RULE_TYPE_LABELS: Record<Api.RuleType, string> = {
  NOT_NULL: '非空校验',
  UNIQUE: '唯一校验',
  ENUM: '枚举值校验',
  LENGTH: '长度校验',
};

export const QUALITY_DIM_LABELS: Record<string, string> = {
  COMPLETENESS: '完整性',
  UNIQUENESS: '唯一性',
  NORMALIZATION: '规范性',
};

export const MASK_REASON_LABELS: Record<string, string> = {
  RULE: '命中数据分类规则',
  UNCLASSIFIED: '字段被标记为敏感',
  UNTRACEABLE: '来源不可追溯且涉及敏感表',
  UNVERIFIED: '敏感性未能核实',
  METADATA: '元数据标注为敏感或已定级',
  MASK_FAILED: '脱敏服务不可用，未保存取值',
};

/** 脱敏原因的展示文本。 */
export function maskReasonLabel(reason?: null | string) {
  if (!reason) return '取值已脱敏';
  return MASK_REASON_LABELS[reason] ?? reason;
}

/** 是否仍需轮询。 */
export function isActiveRun(run?: Api.Run | null) {
  return run?.status === 'PENDING' || run?.status === 'RUNNING';
}

const DECIMAL = /^(\d+)(?:\.(\d+))?$/;

/**
 * 十进制比率字符串（0~1）→ 百分比文本，纯字符串/BigInt 运算、四舍五入，不经过浮点。
 * 例：'0.012500' → '1.25%'，'1.000000' → '100%'。非法或空值返回 '-'。
 */
export function ratioToPercent(ratio?: null | string, digits = 2): string {
  const value = percentDigits(ratio, digits);
  return value === undefined ? '-' : `${value}%`;
}

/** 分布条宽度（CSS 百分比，限制在 0~100）。 */
export function ratioWidth(ratio?: null | string): string {
  const value = percentDigits(ratio, 2);
  if (value === undefined) return '0%';
  const whole = value.split('.')[0] ?? '0';
  return BigInt(whole) >= 100n ? '100%' : `${value}%`;
}

function percentDigits(ratio: null | string | undefined, digits: number) {
  const match = ratio === null || ratio === undefined ? null : DECIMAL.exec(ratio.trim());
  if (!match) return undefined;
  const integer = match[1] ?? '0';
  const fraction = match[2] ?? '';
  const scale = digits + 2;
  let units = BigInt(integer + fraction.slice(0, scale).padEnd(scale, '0'));
  if ((fraction[scale] ?? '0') >= '5') units += 1n;
  const text = units.toString().padStart(digits + 1, '0');
  const whole = digits === 0 ? text : text.slice(0, -digits);
  const decimals = digits === 0 ? '' : text.slice(-digits).replace(/0+$/, '');
  return decimals ? `${whole}.${decimals}` : whole;
}

/** 规则建议参数的展示文本。 */
export function formatSuggestionParams(params: Record<string, unknown>) {
  return JSON.stringify(params);
}

/** 数值的展示文本，空值显示 '-'。 */
export function display(value?: null | number | string) {
  return value === null || value === undefined || value === '' ? '-' : String(value);
}
