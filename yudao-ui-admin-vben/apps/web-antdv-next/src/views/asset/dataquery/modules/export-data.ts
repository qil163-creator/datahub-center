import { toTimestamp } from '#/views/dashboard/shared/module-overview/values';

/** 导出申请状态文案（与后端 DataExportRequestDO 一致）。 */
const STATUS: Record<string, { color: string; label: string }> = {
  CANCELED: { color: 'default', label: '已撤回' },
  DOWNLOADED: { color: 'blue', label: '已下载' },
  FAILED: { color: 'red', label: '流程未能发起' },
  PENDING: { color: 'orange', label: '审批中' },
  REJECTED: { color: 'red', label: '已驳回' },
};

export function exportStatusView(
  record: { expireTime?: number | string; status: string },
  now = Date.now(),
): {
  color: string;
  downloadUntil?: number;
  label: string;
} {
  if (record.status === 'APPROVED') {
    const expire = toTimestamp(record.expireTime);
    if (expire === undefined)
      return { color: 'default', label: '已批准，下载不可用' };
    return expire > now
      ? { color: 'green', downloadUntil: expire, label: '已批准，可下载' }
      : { color: 'default', label: '下载已过期' };
  }
  return STATUS[record.status] ?? { color: 'default', label: record.status };
}

/** 申请表单校验。 */
export function exportFormError(
  sql: string,
  reason: string,
  limit: number,
): string | undefined {
  if (!sql.trim()) return '请先在编辑器中输入要导出的 SELECT';
  if (!reason.trim()) return '请填写申请原因';
  if (!Number.isInteger(limit) || limit < 1 || limit > 1000)
    return '导出行数应为 1-1000';
  return undefined;
}
