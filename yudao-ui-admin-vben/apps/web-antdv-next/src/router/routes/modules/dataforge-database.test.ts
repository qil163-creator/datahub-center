import { createMemoryHistory, createRouter } from 'vue-router';

import { findRootMenuByPath } from '@vben/utils';

import { describe, expect, it } from 'vitest';

import routes from './dataforge-database';

describe('database detail route', () => {
  it.each([
    {
      name: 'BasicDataforgeDatabaseDetail',
      detailPath: '/basic/database/detail',
      listPath: '/basic/database',
      rootPath: '/basic',
    },
    {
      name: 'DataforgeDatabaseDetail',
      detailPath: '/develop/databases/database/detail',
      listPath: '/develop/database',
      rootPath: '/develop',
    },
  ])(
    'keeps $name in its own module and preserves large IDs',
    ({ name, detailPath, listPath, rootPath }) => {
      const router = createRouter({
        history: createMemoryHistory(),
        routes,
      });
      const id = '9007199254740993';
      const resolved = router.resolve({
        name,
        params: { id },
      });

      expect(resolved.path).toBe(`${detailPath}/${id}`);
      expect(resolved.params.id).toBe(id);
      expect(resolved.meta.activePath).toBe(listPath);
      expect(resolved.meta.hideInMenu).toBe(true);
      const listMenu = {
        name: '数据连接',
        path: listPath,
        parents: [rootPath],
      };
      const menus = [
        {
          name: '当前模块',
          path: rootPath,
          children: [listMenu],
        },
      ];

      const { findMenu, rootMenu } = findRootMenuByPath(
        menus,
        resolved.meta.activePath,
      );

      expect(findMenu).toBe(listMenu);
      expect(rootMenu?.path).toBe(rootPath);
      expect(rootMenu?.children).toContain(listMenu);

      const direct = router.resolve(resolved.path);
      expect(direct.name).toBe(name);
      expect(direct.meta.activePath).toBe(listPath);
      expect(direct.params.id).toBe(id);
    },
  );
});
