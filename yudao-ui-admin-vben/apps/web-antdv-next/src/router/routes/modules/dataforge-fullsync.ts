import type { RouteRecordRaw } from 'vue-router';

const activePath = '/develop/integrate/full-sync';
const routes: RouteRecordRaw[] = [
  {
    path: '/dataforge/full-sync/wizard/:id?',
    name: 'DataforgeFullSyncWizard',
    component: () => import('#/views/dataforge/fullsync/wizard.vue'),
    meta: {
      title: '整库同步任务配置',
      hideInMenu: true,
      activePath,
      fullPathKey: true,
    },
  },
  {
    path: `${activePath}/detail/:id`,
    name: 'DataforgeFullSyncDetail',
    component: () => import('#/views/dataforge/fullsync/detail.vue'),
    meta: {
      title: '整库同步任务详情',
      hideInMenu: true,
      activePath,
      fullPathKey: false,
    },
  },
];
export default routes;
