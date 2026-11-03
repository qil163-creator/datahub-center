import type { RouteRecordRaw } from 'vue-router';

const activePath = '/develop/job';
const routes: RouteRecordRaw[] = [
  {
    path: '/dataforge/job/designer/:id',
    name: 'DataforgeJobDesigner',
    component: () => import('#/views/dataforge/job/designer.vue'),
    meta: {
      title: '作业管理配置',
      hideInMenu: true,
      activePath,
      fullPathKey: true,
    },
  },
  {
    path: '/dataforge/job/detail/:id',
    name: 'DataforgeJobDetail',
    component: () => import('#/views/dataforge/job/detail.vue'),
    meta: {
      title: '作业管理详情',
      hideInMenu: true,
      activePath,
      fullPathKey: true,
    },
  },
];

export default routes;
