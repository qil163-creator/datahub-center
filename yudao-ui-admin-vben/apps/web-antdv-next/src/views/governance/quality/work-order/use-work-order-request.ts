export function createWorkOrderRequestGuard() {
  let disposed = false;
  let sequence = 0;

  return {
    begin() {
      sequence += 1;
      return sequence;
    },
    dispose() {
      disposed = true;
      sequence += 1;
    },
    isLatest(requestSequence: number) {
      return !disposed && requestSequence === sequence;
    },
  };
}

export function losslessId(value: null | number | string | undefined): string {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value);
}

export const OLD_PROCESS_CLOSED = '旧处理入口已关闭，请走整改提交与验收';
export const OLD_TRANSFER_CLOSED = '旧转办入口已关闭，请走同一工单链转派';
