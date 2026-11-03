import type { SetupContext } from 'vue';

import { createApp, nextTick } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import TextEllipsisCell from './text-ellipsis-cell.vue';

vi.mock('antdv-next', () => ({
  Tooltip: (_: unknown, { slots }: SetupContext) => slots.default?.(),
}));

async function mountCell(props: {
  center: boolean;
  lineHeight?: number;
  reserveLines?: boolean;
}) {
  const host = document.createElement('div');
  const app = createApp(TextEllipsisCell, { ...props, content: '描述' });
  app.mount(host);
  await nextTick();
  const wrapper = host.firstElementChild as HTMLElement;
  return { app, wrapper, text: wrapper.firstElementChild as HTMLElement };
}

describe('文本省略单元格行高', () => {
  it.each([false, true])('默认行高和两行预留保持不变，center=%s', async (center) => {
    const { app, wrapper, text } = await mountCell({ center });
    try {
      expect(text.style.lineHeight).toBe('24px');
      expect(center ? wrapper.style.minHeight : text.style.minHeight).toBe('48px');
    } finally {
      app.unmount();
    }
  });

  it.each([false, true])('可按内容高度显示，center=%s', async (center) => {
    const { app, wrapper, text } = await mountCell({
      center,
      lineHeight: 18,
      reserveLines: false,
    });
    try {
      expect(text.style.lineHeight).toBe('18px');
      expect(wrapper.style.minHeight).toBe('');
      expect(text.style.minHeight).toBe('');
    } finally {
      app.unmount();
    }
  });
});
