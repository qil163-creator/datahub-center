import type { StandardDocApi } from '#/api/governance/doc';

/** 类型筛选只在当前登记确实不匹配时解除关联，不能由分页未命中或读取失败推断。 */
export function createDocumentBindingValidator(
  load: (id: StandardDocApi.Id) => Promise<{ stdType: string }>,
) {
  let sequence = 0;
  return async (
    values: { documentId?: StandardDocApi.Id; stdType?: string },
    clear: () => unknown,
  ) => {
    const current = ++sequence;
    const { documentId, stdType } = values;
    if (!stdType || documentId === undefined) return;
    try {
      const doc = await load(documentId);
      if (current === sequence && doc.stdType !== stdType) await clear();
    } catch {
      // 保留已有登记，等待用户重试读取或明确解除关联。
    }
  };
}
