/** 密钥引用编码：字母开头，字母数字下划线或短横线，≤64（与后端一致）。 */
export const KEY_CODE_PATTERN = /^[A-Za-z][\w-]{0,63}$/;

/** 新建表单校验。 */
export function keyRefFormError(
  keyCode: string,
  keyName: string,
): string | undefined {
  if (!KEY_CODE_PATTERN.test(keyCode.trim())) {
    return '引用编码须字母开头，只含字母、数字、下划线或短横线（≤64）';
  }
  if (!keyName.trim()) return '请输入名称';
  if (keyName.trim().length > 128) return '名称不能超过 128 个字符';
  return undefined;
}
