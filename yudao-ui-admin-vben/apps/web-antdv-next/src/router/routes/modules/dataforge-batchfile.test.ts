import { createMemoryHistory, createRouter } from 'vue-router';

import { describe, expect, it } from 'vitest';

import routes from './dataforge-batchfile';

describe('批量文件入库辅助页面路由', () => {
  it('保留导航和大整数编号且不增加静态菜单', () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    for (const name of ['DataforgeBatchFileDetail', 'DataforgeBatchFileEdit']) {
      const route = router.resolve({
        name,
        params: { id: '9007199254740993' },
      });
      expect(route.params.id).toBe('9007199254740993');
      expect(route.meta.activePath).toBe('/develop/integrate/batch-file');
      expect(route.meta.hideInMenu).toBe(true);
    }
    expect(router.resolve({ name: 'DataforgeBatchFileCreate' }).path).toBe(
      '/develop/integrate/batch-file/create',
    );
    expect(router.resolve({ name: 'DataforgeBatchFileDetail', params: { id: '1' }, query: { tab: 'runs' } }).meta.fullPathKey).toBe(false);
  });
});
