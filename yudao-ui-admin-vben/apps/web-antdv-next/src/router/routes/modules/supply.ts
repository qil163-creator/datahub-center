import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/supply/api-log/detail/:id',
    name: 'SupplyApiLogDetailPage',
    component: () => import('#/views/supply/apilog/detail.vue'),
    meta: {
      title: '调用记录详情',
      hideInMenu: true,
      keepAlive: false,
      activePath: '/supply/api-log',
    },
  },
  {
    path: '/supply/app/detail/:id',
    name: 'SupplyAppDetailPage',
    component: () => import('#/views/supply/app/detail.vue'),
    meta: {
      title: '应用详情',
      hideInMenu: true,
      keepAlive: false,
      activePath: '/supply/app',
    },
  },
  {
    path: '/supply/data-api/detail/:id',
    name: 'SupplyDataApiDetailPage',
    component: () => import('#/views/supply/dataapi/detail.vue'),
    meta: {
      title: 'API服务详情',
      hideInMenu: true,
      keepAlive: false,
      activePath: '/supply/data-api',
    },
  },
];

export default routes;
