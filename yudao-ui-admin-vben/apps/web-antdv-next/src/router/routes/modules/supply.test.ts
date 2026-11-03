import { createMemoryHistory, createRouter } from 'vue-router';

import { describe, expect, it } from 'vitest';

import routes from './supply';

describe('数据服务详情辅助路由', () => {
  it.each([
    {
      name: 'SupplyAppDetailPage',
      activePath: '/supply/app',
      title: '应用详情',
    },
    {
      name: 'SupplyDataApiDetailPage',
      activePath: '/supply/data-api',
      title: 'API服务详情',
    },
  ])('无损保留 $name 编号并高亮对应管理菜单', ({ name, activePath, title }) => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    const id = '9007199254740993';
    const resolved = router.resolve({
      name,
      params: { id },
    });

    expect(resolved.path).toBe(`${activePath}/detail/${id}`);
    expect(resolved.params.id).toBe(id);
    expect(resolved.meta.activePath).toBe(activePath);
    expect(resolved.meta.hideInMenu).toBe(true);
    expect(resolved.meta.keepAlive).toBe(false);
    expect(resolved.meta.title).toBe(title);
  });
});
