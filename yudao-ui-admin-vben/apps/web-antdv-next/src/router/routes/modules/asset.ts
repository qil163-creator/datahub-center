import type { RouteRecordRaw } from 'vue-router';

import { useAccessStore, useUserStore } from '@vben/stores';

import { parseResourceId } from '#/utils/resource-id';

const routes: RouteRecordRaw[] = [
  {
    path: '/asset',
    name: 'AssetCenter',
    redirect: '/asset/map',
    meta: {
      title: '数据资产',
      hideInMenu: true,
    },
    children: [
      {
        path: 'market/map/detail/:id',
        name: 'AssetMarketMapDetail',
        component: () => import('#/views/asset/market/map/detail/index.vue'),
        meta: {
          title: '资产地图详情',
          hideInMenu: true,
          keepAlive: false,
          activePath: '/asset/map',
        },
        beforeEnter(to) {
          if (!parseResourceId(to.params.id))
            return { path: '/404', replace: true };
          const access = useAccessStore();
          if (
            !access.accessToken ||
            access.loginExpired ||
            !parseResourceId(useUserStore().userInfo?.userId)
          )
            return { path: '/403', replace: true };
          return true;
        },
      },
      {
        path: 'market/detail/:id',
        name: 'AssetMarketDetailPage',
        component: () => import('#/views/asset/market/detail/index.vue'),
        meta: {
          title: '集市资产详情',
          hideInMenu: true,
          keepAlive: false,
          activePath: '/asset/mart',
        },
        beforeEnter(to) {
          if (!parseResourceId(to.params.id))
            return { path: '/404', replace: true };
          const access = useAccessStore();
          if (
            !access.accessToken ||
            access.loginExpired ||
            !parseResourceId(useUserStore().userInfo?.userId)
          )
            return { path: '/403', replace: true };
          return true;
        },
      },
      {
        path: 'tag/detail/:id',
        name: 'AssetTagDetail',
        component: () => import('#/views/asset/tag/detail/index.vue'),
        meta: {
          title: '标签详情',
          hideInMenu: true,
          activePath: '/asset/tag',
        },
      },
    ],
  },
];

export default routes;
