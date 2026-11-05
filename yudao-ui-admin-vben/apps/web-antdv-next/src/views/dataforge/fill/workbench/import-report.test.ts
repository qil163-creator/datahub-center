import { describe, expect, it } from 'vitest';

import { buildImportIssueReport } from './import-report';

describe('Excel 导入错误报告', () => {
  it('带 BOM，列名映射为表头名，转义逗号引号并防公式注入', () => {
    const csv = buildImportIssueReport(
      [
        { ruleCode: 'REQUIRED', severity: 1, displayRowNo: 3, fieldCode: 'f1', message: '必填, 不能为空' },
        { ruleCode: 'REGEX', severity: 2, fieldCode: 'zz', message: '=cmd|"x"' },
      ],
      { f1: '姓名' },
    );
    const lines = csv.slice(1).trimEnd().split('\r\n');
    expect(csv.startsWith('﻿')).toBe(true);
    expect(lines[0]).toBe('Excel行号,列,级别,规则,问题说明');
    expect(lines[1]).toBe('3,姓名,错误,REQUIRED,"必填, 不能为空"');
    expect(lines[2]).toBe(`,zz,警告,REGEX,"'=cmd|""x"""`);
  });
});
