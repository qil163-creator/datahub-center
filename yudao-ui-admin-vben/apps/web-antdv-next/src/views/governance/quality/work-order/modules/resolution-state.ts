import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

export const SUBMISSION_LABELS: Record<
  QualityWorkOrderApi.SubmissionType,
  string
> = {
  SOURCE_FIX: '源端整改',
  PLATFORM_REPAIR: '平台修复',
  EXPLAIN: '说明确认',
  EXCEPTION: '例外申请',
};
export const CLOSE_LABELS: Record<string, string> = {
  VERIFIED_PASS: '质量通过',
  EXPLAINED: '说明被接受（不代表质量通过）',
  EXCEPTION_ACCEPTED: '例外被接受（不代表质量通过）',
  LEGACY_PROCESSED: '历史关闭（缺少本次验收凭证）',
};
export const RECHECK_LABELS: Record<string, string> = {
  QUEUED: '待复检',
  RUNNING: '复检中',
  PASS: '复检通过',
  FAIL: '复检未通过',
  WAITING_DATA: '等待数据 / 尚不可比',
  ERROR: '复检异常',
  OBSOLETE: '已失效轮次',
};

/** 后端待确认类型与提交必须一致；未知类型绝不默认当成质量通过。 */
export function acceptance(order: QualityWorkOrderApi.WorkOrder):
  | undefined
  | {
      action: QualityWorkOrderApi.ConfirmAction;
      label: string;
    } {
  const sub = order.currentSubmission;
  if (
    !sub ||
    sub.id === undefined ||
    sub.id === null ||
    sub.status !== 'ACTIVE' ||
    String(sub.id) !== String(order.currentSubmissionId)
  )
    return;
  if (
    order.confirmationPendingType === 'EXPLAIN' &&
    sub.submissionType === 'EXPLAIN' &&
    sub.content?.trim()
  ) {
    return { action: 'ACCEPT_EXPLAIN', label: '接受说明' };
  }
  if (
    order.confirmationPendingType === 'EXCEPTION' &&
    sub.submissionType === 'EXCEPTION' &&
    sub.content?.trim() &&
    sub.exceptionScope?.trim() &&
    sub.exceptionExpireAt &&
    sub.exceptionExpireAt > Date.now()
  ) {
    return { action: 'ACCEPT_EXCEPTION', label: '接受例外' };
  }
  if (
    order.confirmationPendingType === 'RECHECK_PASS' &&
    ['PLATFORM_REPAIR', 'SOURCE_FIX'].includes(sub.submissionType ?? '') &&
    sub.instanceId !== undefined &&
    sub.instanceId !== null &&
    sub.recheckStatus === 'PASS' &&
    sub.comparableFlag === 1 &&
    sub.rechecks?.some(
      (attempt) =>
        String(attempt.instanceId) === String(sub.instanceId) &&
        attempt.status === 'PASS' &&
        attempt.comparableFlag === 1 &&
        !!attempt.evidenceJson,
    )
  ) {
    return { action: 'VERIFY_PASS', label: '确认质量通过' };
  }
}

/** 仅摘取后端提供的原因码，不把原始凭证作为 HTML 或问题原文渲染。 */
export function evidenceReasons(json?: string): string {
  if (!json) return '未提供判定凭证';
  try {
    const parsed: unknown = JSON.parse(json);
    if (
      !parsed ||
      typeof parsed !== 'object' ||
      !('checks' in parsed) ||
      !Array.isArray(parsed.checks)
    )
      return '凭证已留存';
    const codes = parsed.checks.flatMap((item: unknown) =>
      item &&
      typeof item === 'object' &&
      'reasonCode' in item &&
      typeof item.reasonCode === 'string'
        ? [item.reasonCode]
        : [],
    );
    return codes.length > 0 ? [...new Set(codes)].join(' / ') : '凭证已留存';
  } catch {
    return '凭证格式不可识别，请刷新或联系管理员';
  }
}
