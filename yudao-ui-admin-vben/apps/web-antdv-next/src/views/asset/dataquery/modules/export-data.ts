import type { DataExportApi } from '#/api/asset/dataexport';

/** 导出申请状态文案（与后端 DataExportRequestDO 一致）。 */
const STATUS: Record<string, { color: string; label: string }> = {
  APPROVED: { color: 'green', label: '已批准，可下载' },
  CANCELED: { color: 'default', label: '已撤回' },
  DOWNLOADED: { color: 'blue', label: '已下载' },
  FAILED: { color: 'red', label: '流程未能发起' },
  PENDING: { color: 'orange', label: '审批中' },
  REJECTED: { color: 'red', label: '已驳回' },
};

export function exportStatusView(status: string): {
  color: string;
  label: string;
} {
  return STATUS[status] ?? { color: 'default', label: status };
}

/** 只有已批准且未过期的申请可下载。 */
export function canDownload(
  record: DataExportApi.Request,
  now = Date.now(),
): boolean {
  if (record.status !== 'APPROVED' || record.expireTime === undefined)
    return false;
  const expire = new Date(
    typeof record.expireTime === 'number'
      ? record.expireTime
      : String(record.expireTime),
  ).getTime();
  return !Number.isNaN(expire) && expire > now;
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
