import type { Router } from 'vue-router';

import { describe, expect, it } from 'vitest';

import { DASHBOARD_TARGETS, resolveRoutePath } from './route-resolver';

describe('首页资产地图导航', () => {
  it('优先使用当前用户真实加载的地图路由，不依赖固定菜单路径', () => {
    const router = {
      getRoutes: () => [
        { name: 'AssetMarketMap', path: '/configured/market/map' },
      ],
    } as unknown as Router;
    expect(resolveRoutePath(router, DASHBOARD_TARGETS.dataAsset)).toBe(
      '/configured/market/map',
    );
  });

  it('保留地图标题回退，无地图访问权时不显示资产入口', () => {
    const router = {
      getRoutes: () => [
        {
          path: '/asset/market/map',
          meta: { title: '资产地图' },
          components: { default: {} },
        },
      ],
    } as unknown as Router;
    expect(resolveRoutePath(router, DASHBOARD_TARGETS.dataAsset)).toBe(
      '/asset/market/map',
    );
    const inaccessible = { getRoutes: () => [] } as unknown as Router;
    expect(
      resolveRoutePath(inaccessible, DASHBOARD_TARGETS.dataAsset),
    ).toBeUndefined();
  });
});

describe('质量报告导航', () => {
  it('根据当前用户的质量报告菜单解析路径，无权限时隐藏入口', () => {
    const accessible = {
      getRoutes: () => [
        { name: 'GovernanceQualityReport', path: '/governance/quality/report' },
      ],
    } as unknown as Router;
    expect(resolveRoutePath(accessible, DASHBOARD_TARGETS.qualityReport)).toBe(
      '/governance/quality/report',
    );
    const inaccessible = {
      getRoutes: () => [
        {
          name: 'GovernanceQualityOverview',
          path: '/governance/quality/monitor',
          meta: { title: '数据质量监控' },
          components: { default: {} },
        },
      ],
    } as unknown as Router;
    expect(
      resolveRoutePath(inaccessible, DASHBOARD_TARGETS.qualityReport),
    ).toBeUndefined();
  });
});

describe('最新元数据导航', () => {
  it('使用最新元数据菜单，无权限时不回退到已删除的概览页面', () => {
    const accessible = {
      getRoutes: () => [
        { name: 'GovernanceMetaObject', path: '/govern/metadata/metaobject' },
      ],
    } as unknown as Router;
    expect(resolveRoutePath(accessible, DASHBOARD_TARGETS.metaObject)).toBe(
      '/govern/metadata/metaobject',
    );
    const inaccessible = {
      getRoutes: () => [
        {
          name: 'GovernanceMetaOverview',
          path: '/govern/metadata/metaoverview',
          meta: { title: '元数据概览' },
          components: { default: {} },
        },
      ],
    } as unknown as Router;
    expect(
      resolveRoutePath(inaccessible, DASHBOARD_TARGETS.metaObject),
    ).toBeUndefined();
  });
});
