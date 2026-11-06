import type { RouteRecordRaw } from 'vue-router';

import { useAccessStore, useUserStore } from '@vben/stores';

import { parseResourceId } from '#/utils/resource-id';

const routes: RouteRecordRaw[] = [
  {
    path: '/market/check/detail/:id',
    name: 'MarketCheckDetail',
    component: () => import('#/views/market/check/detail/index.vue'),
    meta: {
      title: '审批工单详情',
      hideInMenu: true,
      keepAlive: false,
      activePath: '/asset/check',
    },
    beforeEnter(to) {
      if (
        !parseResourceId(to.params.id) ||
        !['NATIVE', 'QDATA'].includes(String(to.query.origin || 'NATIVE')) ||
        Array.isArray(to.query.origin)
      )
        return { path: '/404', replace: true };
      const access = useAccessStore();
      if (
        !access.accessToken ||
        access.loginExpired ||
        !access.accessCodes.includes('asset:market:check') ||
        !parseResourceId(useUserStore().userInfo?.userId)
      )
        return { path: '/403', replace: true };
      return true;
    },
  },
];

export default routes;
