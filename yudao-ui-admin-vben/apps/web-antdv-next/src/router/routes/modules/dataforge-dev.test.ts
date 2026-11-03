import { createMemoryHistory, createRouter } from 'vue-router';

import { describe, expect, it } from 'vitest';

import routes from './dataforge-dev';

describe('数据开发详情路由', () => {
  it('保留大整数任务编号，并关联数据开发菜单', () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    const id = '9007199254740993';
    const resolved = router.resolve({
      name: 'DataforgeDevTaskDetail',
      params: { id },
    });
    expect(resolved.path).toBe(`/develop/dev/detail/${id}`);
    expect(resolved.params.id).toBe(id);
    expect(resolved.meta.activePath).toBe('/develop/dev/workbench');
    expect(resolved.meta.hideInMenu).toBe(true);
  });
});
