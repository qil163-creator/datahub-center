import type { DataforgeFillApi } from '#/api/dataforge/fill';

/** CSV 单元格转义；以公式触发字符开头的内容加前缀，避免在 Excel 中被当作公式执行。 */
function csvCell(value: unknown): string {
  let text = value === undefined || value === null ? '' : String(value);
  if (/^[=+\-@\t\r]/u.test(text)) text = `'${text}`;
  return /[",\r\n]/u.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

/**
 * 生成 Excel 导入错误报告（CSV，UTF-8 带 BOM，Excel 直接打开不乱码）。
 * 对比弹窗只展示前 100 条，报告包含全部问题，便于分批修正。
 */
export function buildImportIssueReport(
  issues: DataforgeFillApi.ValidationIssue[],
  fieldNames: Record<string, string>,
): string {
  const header = ['Excel行号', '列', '级别', '规则', '问题说明'];
  const lines = issues.map((issue) =>
    [
      issue.displayRowNo ?? '',
      (issue.fieldCode && fieldNames[issue.fieldCode]) || issue.fieldCode || '',
      issue.severity === 2 ? '警告' : '错误',
      issue.ruleCode,
      issue.message,
    ]
      .map((cell) => csvCell(cell))
      .join(','),
  );
  return `﻿${[header.join(','), ...lines].join('\r\n')}\r\n`;
}
