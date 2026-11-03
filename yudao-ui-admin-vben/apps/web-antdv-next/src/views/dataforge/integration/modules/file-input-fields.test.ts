import { describe, expect, it } from 'vitest';

import {
  fileInputFieldTypes,
  safeFileDownloadUrl,
  validateFileInputDates,
} from './file-input-fields';

describe('文件输入字段与附件边界', () => {
  it('参考类型使用原始code，历史类型不转换，只有日期字段要求格式', () => {
    expect(fileInputFieldTypes.map((item) => item.value)).toEqual([
      'long',
      'boolean',
      'string',
      'date',
      'double',
    ]);
    expect(validateFileInputDates([{ name: 'old', type: 'Integer' }])).toEqual(
      [],
    );
    expect(validateFileInputDates([{ name: 'created', type: 'date' }])).toEqual(
      ['第1行日期格式不能为空'],
    );
    expect(
      validateFileInputDates([
        { name: 'created', type: 'Timestamp', format: 'yyyy-MM-dd HH:mm:ss' },
      ]),
    ).toEqual([]);
  });

  it('只接受真实HTTP附件URL，拒绝脚本、内联数据、嵌入凭据和无效地址', () => {
    const base = 'http://localhost:5999/';
    expect(
      safeFileDownloadUrl('/admin-api/infra/file/1/get/file.xlsx', base),
    ).toBe('http://localhost:5999/admin-api/infra/file/1/get/file.xlsx');
    expect(
      safeFileDownloadUrl(
        'https://storage.example/file.xlsx?signature=example',
        base,
      ),
    ).toBe('https://storage.example/file.xlsx?signature=example');
    for (const url of [
      'javascript:alert(1)',
      ' data:text/html,<script>',
      'file:///tmp/file.xlsx',
      'https://user:password@storage.example/file.xlsx',
      'http://[invalid',
      undefined,
      '',
    ])
      expect(safeFileDownloadUrl(url, base)).toBeUndefined();
  });
});
