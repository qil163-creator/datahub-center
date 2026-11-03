import type { NavigationGuard, RouteLocationNormalized } from 'vue-router';

import { createMemoryHistory, createRouter } from 'vue-router';

import { describe, expect, it } from 'vitest';

import routes from './modeling';

describe('modeling route retirement', () => {
  const children = routes[0]!.children!;

  it('opens the registered unified model list when entering the modeling center', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        ...routes,
        {
          path: '/model/models/logic-model',
          name: 'ModelingTableModel',
          component: { render: () => null },
        },
      ],
    });
    await router.push('/modeling');
    expect(router.currentRoute.value.name).toBe('ModelingTableModel');
  });

  it('does not route retired concept identifiers into unified model identifiers', () => {
    expect(children.some((route) => route.path.includes('concept'))).toBe(
      false,
    );
    expect(
      children.find((route) => route.name === 'ModelingTableModelDetail')?.path,
    ).toBe('table-model/detail/:id');
  });

  it('业务规划详情仅注册隐藏辅助路由并保持原列表高亮', () => {
    const domain = children.find((route) => route.name === 'ModelingDataDomainDetail');
    const subject = children.find((route) => route.name === 'ModelingSubjectDomainDetail');
    expect(domain?.path).toBe('data-domain/detail/:id');
    expect(subject?.path).toBe('subject-domain/detail/:id');
    expect(domain?.meta).toMatchObject({ hideInMenu: true, activePath: '/model/warehouse-plan/data-domain' });
    expect(subject?.meta).toMatchObject({ hideInMenu: true, activePath: '/model/warehouse-plan/subject-domain' });
  });

  it('直接进入详情或从其他详情跳转时始终归属可见建模菜单', () => {
    for (const name of ['ModelingTableModelDetail', 'ModelingTableModelEr']) {
      const detail = children.find((route) => route.name === name)!;
      expect(detail.meta?.activePath).toBe('/model/models');
      expect(detail.beforeEnter).toBeUndefined();
    }
    expect(children.find((route) => route.name === 'ModelingMaterializedDetail')?.meta?.activePath)
      .toBe('/model/models/model-materialized');
  });

  it.each(['source', 'dimension', 'fact', 'summary', 'application'])(
    '编辑 %s 时选中对应列表，忽略之前无关详情的地址',
    (kind) => {
      const edit = children.find((route) => route.name === 'ModelingLogicTableEdit')!;
      const to = { params: { kind }, meta: { ...edit.meta } } as unknown as RouteLocationNormalized;
      const from = { path: '/modeling/table-model/detail/1', meta: {} } as RouteLocationNormalized;
      (edit.beforeEnter as NavigationGuard).call(undefined, to, from, () => {});
      expect(to.meta.activePath).toBe(`/model/models/${kind}-table`);
    },
  );


});
