/** 表清单类型列：与预览弹窗参考图一致 */
export function tableTypeLabel(viewTable?: boolean) {
  return viewTable ? 'VIEW' : 'BASE TABLE';
}

export function displayText(value?: null | number | string) {
  if (value === null || value === undefined) {
    return '-';
  }
  const text = String(value).trim();
  return text || '-';
}

export function yesNo(value?: boolean) {
  if (value === undefined) {
    return '-';
  }
  return value ? '是' : '否';
}

export function formatPreviewCell(value: unknown) {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  if (
    typeof value === 'string' ||
    typeof value === 'number' ||
    typeof value === 'boolean'
  ) {
    return String(value);
  }
  return String(value);
}

const FALLBACK_CONNECT_HINT =
  '无法连接该数据库，请先进行连接测试，并检查主机、端口、账号和密码。';

export function resolvePreviewError(error: unknown) {
  if (error instanceof Error) {
    const text = error.message.trim();
    if (text && text !== '系统异常') {
      return text;
    }
  }
  return FALLBACK_CONNECT_HINT;
}
