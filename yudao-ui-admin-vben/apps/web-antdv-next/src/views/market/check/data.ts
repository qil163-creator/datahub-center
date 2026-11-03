import type { MarketCheckApi } from '#/api/asset/market/check';

const sourceTimeFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

export const CHECK_STATUS_OPTIONS = [
  { value: '1', label: '未授权', color: 'default' },
  { value: '2', label: '待授权', color: 'warning' },
  { value: '3', label: '已通过', color: 'success' },
  { value: '4', label: '已驳回', color: 'error' },
  { value: '5', label: '已下架', color: 'default' },
  { value: '6', label: '已过期', color: 'default' },
] as const;

export function marketCheckStatusColor(value: string) {
  return (
    CHECK_STATUS_OPTIONS.find((item) => item.value === value)?.color ??
    'default'
  );
}

export function marketCheckStatusLabel(ticket: MarketCheckApi.Ticket) {
  return (
    ticket.statusLabel ||
    CHECK_STATUS_OPTIONS.find((item) => item.value === ticket.status)?.label ||
    ticket.status ||
    '-'
  );
}

export function formatMarketCheckTime(value?: MarketCheckApi.Time) {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'string') {
    if (/^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(value))
      return value.slice(0, 19).replace('T', ' ');
    if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return value;
  }
  let millis: number;
  if (typeof value === 'number') millis = value;
  else if (/^\d+$/.test(value)) millis = Number(value);
  else millis = Date.parse(value);
  if (!Number.isFinite(millis) || Number.isNaN(new Date(millis).getTime()))
    return String(value);
  const parts = Object.fromEntries(
    sourceTimeFormat
      .formatToParts(millis)
      .map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}

export function marketCheckTime(value?: MarketCheckApi.Time) {
  const text = formatMarketCheckTime(value);
  return /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(text)
    ? text.slice(0, 16)
    : text;
}

export function marketCheckPeriod(ticket: MarketCheckApi.Ticket) {
  if (ticket.applyType === 1) return '长期有效';
  return `${marketCheckTime(ticket.startTime)} ~ ${marketCheckTime(ticket.endTime)}`;
}

export function marketCheckRowKey(ticket: MarketCheckApi.Ticket) {
  return `${ticket.origin}:${ticket.id}`;
}

export function canReviewMarketCheck(
  ticket: MarketCheckApi.Ticket,
  status: '3' | '4',
) {
  return (
    ['1', '2'].includes(ticket.status) &&
    (status === '3' ? ticket.canApprove : ticket.canReject)
  );
}

export function marketCheckReviewError(value: string, status: '3' | '4') {
  const reason = value.trim();
  if (!reason) return status === '4' ? '请输入驳回理由' : '请输入审批意见';
  if (reason.length > 256) return '审批意见不能超过256个字符';
  return '';
}
