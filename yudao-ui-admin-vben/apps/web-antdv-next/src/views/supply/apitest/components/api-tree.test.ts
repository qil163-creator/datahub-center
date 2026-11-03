import type { App } from 'vue';

import type { SupplyApiTestApi } from '#/api/supply/apitest';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ApiTree from './api-tree.vue';

vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));

const parentId = '9007199254740993123';
const childId = '9223372036854775806';
const apiId = '9223372036854775807';
function api(
  id: number | string,
  name: string,
  path: string,
  categoryId?: number | string,
): SupplyApiTestApi.Api {
  return {
    id,
    name,
    path,
    categoryId,
    method: 'GET',
    version: 'v1',
    authType: 'NONE',
    configType: 'TABLE',
    resultFormat: 'LIST',
    status: 1,
  };
}
function catalog(): SupplyApiTestApi.Catalog {
  return {
    publicBaseUrl: 'https://example.test',
    categories: [
      { id: parentId, name: '业务服务' },
      { id: childId, parentId, name: '订单服务' },
      { id: 20, name: '其他服务' },
    ],
    apis: [
      api(apiId, '订单查询', '/ORDERS/search', childId),
      api(22, '客户查询', '/customers', 20),
      api(23, '无类目接口', '/other', 999),
    ],
  };
}

let app: App | undefined;
let host: HTMLDivElement;
let state: {
  catalog?: SupplyApiTestApi.Catalog;
  loading: boolean;
  selectedApiId?: string;
};
const select = vi.fn<(id: string) => void>();
const refresh = vi.fn();
async function flush() {
  for (let index = 0; index < 4; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount(initial = catalog()) {
  select.mockClear();
  refresh.mockClear();
  state = reactive({ catalog: initial, loading: false });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    render: () =>
      h(ApiTree, {
        ...state,
        onSelect: (id: string) => {
          select(id);
          state.selectedApiId = id;
        },
        onRefresh: refresh,
      }),
  });
  app.mount(host);
  await flush();
}
async function search(value: string) {
  const input = host.querySelector<HTMLInputElement>(
    'input[aria-label="搜索 API 名称或路径"]',
  )!;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}
function labels() {
  return [...host.querySelectorAll<HTMLElement>('.test-tree__node')].map(
    (node) => node.textContent?.trim(),
  );
}
async function clickTool(title: string) {
  host.querySelector<HTMLButtonElement>(`button[title="${title}"]`)!.click();
  await flush();
}
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('接口测试目录实际树渲染', () => {
  it('展示嵌套分组和未分类接口，并通过真实 Tree 选择无损大 ID', async () => {
    await mount();
    expect(labels()).toEqual([
      '业务服务',
      '订单服务',
      '订单查询',
      '其他服务',
      '客户查询',
      '未分类',
      '无类目接口',
    ]);
    const leaf = [
      ...host.querySelectorAll<HTMLElement>('.test-tree__node'),
    ].find((node) => node.textContent === '订单查询')!;
    leaf.closest<HTMLElement>('.ant-tree-node-content-wrapper')!.click();
    await flush();
    expect(select).toHaveBeenCalledExactlyOnceWith(apiId);
    expect(
      host.querySelector('.ant-tree-node-selected')?.textContent,
    ).toContain('订单查询');
    const category = [
      ...host.querySelectorAll<HTMLElement>('.test-tree__node'),
    ].find((node) => node.textContent === '订单服务')!;
    category.closest<HTMLElement>('.ant-tree-node-content-wrapper')!.click();
    await flush();
    expect(select).toHaveBeenCalledOnce();
  });

  it('按路径忽略大小写搜索，保留祖先，并让类目搜索包含完整子树', async () => {
    await mount();
    await search('  /orders/SEARCH  ');
    expect(labels()).toEqual(['业务服务', '订单服务', '订单查询']);
    await search('业务服务');
    expect(labels()).toEqual(['业务服务', '订单服务', '订单查询']);
    await search('不存在的路径');
    expect(host.textContent).toContain('没有匹配的 API');
    expect(labels()).toEqual([]);
  });

  it('搜索临时展开命中路径，清空搜索后恢复原有收起状态', async () => {
    await mount();
    await clickTool('全部收起');
    expect(labels()).toEqual(['业务服务', '其他服务', '未分类']);
    await search('订单查询');
    expect(labels()).toEqual(['业务服务', '订单服务', '订单查询']);
    await search('');
    expect(labels()).toEqual(['业务服务', '其他服务', '未分类']);
    await clickTool('全部展开');
    expect(labels()).toContain('订单查询');
  });

  it('异常环、孤立父类目和混合类型重复编号不会隐藏接口或重复渲染', async () => {
    await mount({
      publicBaseUrl: '',
      categories: [
        { id: 1, parentId: 2, name: '环类目一' },
        { id: 2, parentId: 1, name: '环类目二' },
        { id: 3, parentId: 2, name: '环内普通子类目' },
        { id: 4, parentId: 4, name: '自环类目' },
        { id: 5, parentId: 999, name: '孤立类目' },
        { id: '1', parentId: 5, name: '重复类目' },
      ],
      apis: [
        api(10, '首个接口', '/first', '1'),
        api('10', '重复接口', '/duplicate', 5),
        api(11, '环子接口', '/child', 3),
        api(12, '自环接口', '/self', 4),
        api(13, '孤立接口', '/orphan', 5),
        api(9_007_199_254_740_992, '已经损失精度的接口', '/unsafe'),
      ],
    });
    expect(labels()).toEqual([
      '环类目一',
      '首个接口',
      '环类目二',
      '环内普通子类目',
      '环子接口',
      '自环类目',
      '自环接口',
      '孤立类目',
      '孤立接口',
    ]);
    await search('/child');
    expect(labels()).toEqual(['环类目二', '环内普通子类目', '环子接口']);
  });

  it('目录失效时清空旧搜索，后续目录从完整展开状态重新展示', async () => {
    await mount();
    await search('订单查询');
    state.catalog = undefined;
    state.loading = true;
    await flush();
    expect(labels()).toEqual([]);
    expect(
      host.querySelector<HTMLInputElement>(
        'input[aria-label="搜索 API 名称或路径"]',
      )?.value,
    ).toBe('');
    state.catalog = {
      publicBaseUrl: '',
      categories: [{ id: 99, name: '新租户类目' }],
      apis: [api(100, '新租户接口', '/new', 99)],
    };
    state.loading = false;
    await flush();
    expect(labels()).toEqual(['新租户类目', '新租户接口']);
    expect(host.textContent).not.toContain('订单查询');
    await clickTool('刷新 API 树');
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('手动收起分支后可全部展开，刷新加载期间阻止重复请求', async () => {
    await mount();
    const category = [...host.querySelectorAll<HTMLElement>('.test-tree__node')]
      .find((node) => node.textContent === '业务服务')!;
    category.closest('.ant-tree-treenode')!
      .querySelector<HTMLElement>('.ant-tree-switcher')!.click();
    await flush();
    expect(host.querySelector('button[title="全部展开"]')).not.toBeNull();
    await clickTool('全部展开');
    expect(labels()).toContain('订单查询');

    state.loading = true;
    await flush();
    await clickTool('刷新 API 树');
    expect(refresh).not.toHaveBeenCalled();
    await clickTool('全部收起');
    expect(labels()).toEqual(['业务服务', '其他服务', '未分类']);

    state.loading = false;
    await flush();
    await clickTool('刷新 API 树');
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('服务端类目、接口名称和路径按普通文本渲染', async () => {
    const name = '<img src=x onerror="alert(1)">';
    await mount({
      publicBaseUrl: '',
      categories: [{ id: 1, name: '<script>alert(1)</script>' }],
      apis: [api(2, name, '/<iframe src=x>', 1)],
    });
    expect(host.textContent).toContain(name);
    expect(host.textContent).toContain('<script>alert(1)</script>');
    expect(host.querySelector('img,script,iframe')).toBeNull();
  });
});
