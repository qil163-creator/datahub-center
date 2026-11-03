export interface SafetyMaskFields {
  ruleName: string;
  applicationScene: string[];
  maskType: '2';
  replaceRule: '1';
  replaceContent: string;
  intervalList: { endNum: null | number; startNum: null | number }[];
  status: number;
  description?: string;
}

export function newSafetyMaskFields(): SafetyMaskFields {
  return {
    ruleName: '',
    applicationScene: [],
    maskType: '2',
    replaceRule: '1',
    replaceContent: '',
    intervalList: [{ startNum: null, endNum: null }],
    status: 1,
    description: '',
  };
}

export function safetyMaskFieldsError(
  form: SafetyMaskFields,
): string | undefined {
  if (!form.ruleName.trim() || form.ruleName.length > 64)
    return '请填写脱敏规则名称（最多64字）';
  if (
    form.applicationScene.length === 0 ||
    form.applicationScene.some((value) => !['1', '2'].includes(value))
  )
    return '请选择应用场景';
  if (!form.replaceContent.trim() || form.replaceContent.length > 256)
    return '请填写替换内容（最多256字）';
  if (
    form.intervalList.length === 0 ||
    form.intervalList.length > 100 ||
    form.intervalList.some(
      (item) =>
        !Number.isSafeInteger(item.startNum) ||
        !Number.isSafeInteger(item.endNum) ||
        item.startNum! < 1 ||
        item.endNum! < item.startNum!,
    )
  )
    return '请填写完整脱敏区间，截止位置需大于等于起始位置且均为正整数';
  if ((form.description?.length || 0) > 256) return '描述最多256字';
  return undefined;
}
