/**
 * 编号类字段（人员、部门、附件、图片）的显示与保存值转换。
 *
 * 单元格保存编号（多个以英文逗号分隔），表格中显示为“名称（编号）”，多个以中文逗号分隔；
 * 编号始终保留在显示文本里，同名人员、同名文件也能区分。编号只按字符串处理，不做数值转换。
 */

const ID_SUFFIX = /（([^（）]+)）$/;

/** 保存值 → 显示文本 */
export function formatRefDisplay(raw: unknown, labels: Map<string, string>): string {
  const ids = String(raw ?? '')
    .split(',')
    .map((id) => id.trim())
    .filter(Boolean);
  return ids.map((id) => `${labels.get(id) ?? '未知'}（${id}）`).join('，');
}

/**
 * 显示文本 → 保存值。
 * 识别“名称（编号）”；只填名称且名称唯一时按名称匹配；都识别不了时原样保留，交给服务端校验报错。
 */
export function parseRefDisplay(display: unknown, labels: Map<string, string>): string {
  const text = String(display ?? '').trim();
  if (!text) return '';
  const byLabel = new Map<string, null | string>();
  for (const [value, label] of labels) {
    byLabel.set(label, byLabel.has(label) ? null : value);
  }
  return text
    .split(/[，,]/)
    .map((segment) => segment.trim())
    .filter(Boolean)
    .map((segment) => {
      const match = ID_SUFFIX.exec(segment);
      if (match) return match[1]!.trim();
      if (labels.has(segment)) return segment;
      return byLabel.get(segment) ?? segment;
    })
    .join(',');
}
