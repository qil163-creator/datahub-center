export const APPLY_STATUS_OPTIONS = [
  { label: '草稿', value: 0, color: 'default' },
  { label: '待审核', value: 1, color: 'processing' },
  { label: '已通过', value: 2, color: 'success' },
  { label: '已驳回', value: 3, color: 'error' },
  { label: '已撤销', value: 4, color: 'default' },
  { label: '已过期', value: 5, color: 'warning' },
  { label: '已收回', value: 6, color: 'error' },
  { label: '待补正', value: 8, color: 'warning' },
];

export const SHARE_ATTRIBUTE_OPTIONS = [
  { label: '待核验', value: 0, color: 'default' },
  { label: '无条件共享', value: 1, color: 'success' },
  { label: '有条件共享', value: 2, color: 'processing' },
  { label: '不予共享', value: 3, color: 'error' },
];

export const DELIVERY_STATUS_OPTIONS = [
  { label: '无交付', value: 0, color: 'default' },
  { label: '待执行', value: 1, color: 'processing' },
  { label: '执行中', value: 2, color: 'cyan' },
  { label: '开通成功', value: 3, color: 'success' },
  { label: '可重试', value: 4, color: 'warning' },
  { label: '待人工', value: 5, color: 'error' },
  { label: '已取消', value: 6, color: 'default' },
];

export function optionLabel(
  options: { label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.label ?? '-';
}

export function optionColor(
  options: { color?: string; label: string; value: number | string }[],
  value?: null | number | string,
) {
  return options.find((item) => item.value === value)?.color ?? 'default';
}
