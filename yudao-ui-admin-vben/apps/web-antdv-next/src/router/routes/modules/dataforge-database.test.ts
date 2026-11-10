import { createMemoryHistory, createRouter } from 'vue-router';

import { findRootMenuByPath } from '@vben/utils';

import { describe, expect, it } from 'vitest';

import routes from './dataforge-database';

describe('database detail route', () => {
  it('resolves the basic management detail with its own active menu and lossless ID', () => {
    const router = createRouter({ history: createMemoryHistory(), routes });
    const id = '9007199254740993';
    const resolved = router.resolve({
      name: 'BasicDataforgeDatabaseDetail',
      params: { id },
    });
    expect(resolved.path).toBe(`/basic/database/detail/${id}`);
    expect(resolved.params.id).toBe(id);
    expect(resolved.meta.activePath).toBe('/basic/database');
    expect(resolved.meta.hideInMenu).toBe(true);
    const listMenu = { name: '数据连接', path: '/basic/database', parents: ['/basic'] };
    const { findMenu, rootMenu } = findRootMenuByPath(
      [{ name: '基础管理', path: '/basic', children: [listMenu] }],
      resolved.meta.activePath,
    );
    expect(findMenu).toBe(listMenu);
    expect(rootMenu?.path).toBe('/basic');
  });
  it('resolves a large database ID losslessly and keeps the list menu active', () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes,
    });
    const id = '9007199254740993';
    const resolved = router.resolve({
      name: 'DataforgeDatabaseDetail',
      params: { id },
    });

    expect(resolved.path).toBe(`/develop/databases/database/detail/${id}`);
    expect(resolved.params.id).toBe(id);
    expect(resolved.meta.activePath).toBe('/develop/database');
    expect(resolved.meta.hideInMenu).toBe(true);
  });

  it('keeps the data development sidebar when opening detail from the backend list menu', () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes,
    });
    const listMenu = {
      name: '数据连接',
      path: '/develop/database',
      parents: ['/develop'],
    };
    const menus = [
      {
        name: '数据开发',
        path: '/develop',
        children: [listMenu],
      },
    ];
    const resolved = router.resolve({
      name: 'DataforgeDatabaseDetail',
      params: { id: '1' },
    });

    const { findMenu, rootMenu } = findRootMenuByPath(
      menus,
      resolved.meta.activePath,
    );

    expect(findMenu).toBe(listMenu);
    expect(rootMenu?.path).toBe('/develop');
    expect(rootMenu?.children).toContain(listMenu);
  });
});
