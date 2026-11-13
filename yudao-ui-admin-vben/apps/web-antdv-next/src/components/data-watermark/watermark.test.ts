import { describe, expect, it } from 'vitest';

import { watermarkBackground, watermarkText } from './watermark';

describe('data watermark', () => {
  it('includes the viewer and minute precision time', () => {
    expect(
      watermarkText(
        { nickname: '张三', userId: 9 },
        new Date(2026, 10, 12, 9, 5),
      ),
    ).toBe('张三 9 2026-11-12 09:05');
    expect(watermarkText(undefined, new Date(2026, 0, 1, 0, 0))).toBe(
      '未登录 2026-01-01 00:00',
    );
  });

  it('escapes markup before embedding it into the svg', () => {
    const background = decodeURIComponent(watermarkBackground('<b>"x"&'));
    expect(background).toContain('&lt;b&gt;&quot;x&quot;&amp;');
    expect(background).not.toContain('<b>');
  });
});
