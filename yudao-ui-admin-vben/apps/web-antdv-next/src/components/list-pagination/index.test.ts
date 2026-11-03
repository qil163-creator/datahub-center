import { createApp, defineComponent, h, nextTick, shallowRef } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import ListPagination from './index.vue';

vi.mock('#/adapter/vxe-table', () => ({
  NativeVxePager: shallowRef(defineComponent({
    emits: ['page-change'],
    setup(_, { emit }) {
      return () => h('div', [
        h('button', { onClick: () => emit('page-change', { currentPage: 3, pageSize: 10 }) }, '下一页'),
        h('button', { onClick: () => emit('page-change', { currentPage: 3, pageSize: 50 }) }, '每页50条'),
      ]);
    },
  })),
}));

describe('独立列表的原生分页适配', () => {
  it.each([
    { index: 0, expected: [3, 10] },
    { index: 1, expected: [1, 50] },
  ])('翻页保留容量，改变容量返回首页：$index', async ({ index, expected }) => {
    const change = vi.fn();
    const updatePage = vi.fn();
    const updateSize = vi.fn();
    const host = document.createElement('div');
    const app = createApp(ListPagination, {
      current: 2,
      pageSize: 10,
      total: 150,
      onChange: change,
      'onUpdate:current': updatePage,
      'onUpdate:pageSize': updateSize,
    });
    app.mount(host);
    try {
      await vi.dynamicImportSettled();
      await nextTick();
      host.querySelectorAll('button')[index]!.click();
      expect(change).toHaveBeenCalledExactlyOnceWith(...expected);
      expect(updatePage).toHaveBeenCalledWith(expected[0]);
      expect(updateSize).toHaveBeenCalledWith(expected[1]);
    } finally {
      app.unmount();
    }
  });
});
