import type { ErTableNode } from './types';

import { beforeAll, describe, expect, it, vi } from 'vitest';

import { registerErShapes } from './er-config';

const shapes = vi.hoisted(() => ({
  render: undefined as
    | ((cell: { getData: () => ErTableNode }) => HTMLElement)
    | undefined,
}));
vi.mock('@antv/x6', () => ({
  Graph: { registerEdge: vi.fn() },
  Shape: {
    HTML: {
      register: (definition: { html: typeof shapes.render }) => {
        shapes.render = definition.html;
      },
    },
  },
}));
beforeAll(registerErShapes);
describe('er comparison html', () => {
  it('renders untrusted names and before/after evidence as text without attribute injection', () => {
    const detail =
      '旧值 " onmouseover="alert(1) <img src=x onerror=alert(1)> 新值';
    const node: ErTableNode = {
      id: '1',
      title: '<script>bad()</script>',
      rows: [
        {
          name: 'id" onclick="bad()',
          change: 'MODIFIED',
          changeDetail: detail,
        },
      ],
    };
    const element = shapes.render!({ getData: () => node });
    expect(
      element.querySelectorAll('script,img,[onclick],[onmouseover]'),
    ).toHaveLength(0);
    expect(element.querySelector('.er-table__row')?.getAttribute('title')).toBe(
      detail,
    );
    expect(element.querySelector('.er-table__title')?.textContent).toBe(
      node.title,
    );
  });
  it('shows removed attributes with a distinct background and strike through', () => {
    const element = shapes.render!({
      getData: () => ({
        id: '1',
        title: '自然人',
        rows: [
          { name: '登记地址', change: 'REMOVED', changeDetail: '删除登记地址' },
        ],
      }),
    });
    const row = element.querySelector<HTMLElement>('.er-table__row');
    expect(row?.style.textDecoration).toBe('line-through');
    expect(row?.style.background).not.toBe('');
    expect(row?.textContent).toContain('登记地址');
  });
});
