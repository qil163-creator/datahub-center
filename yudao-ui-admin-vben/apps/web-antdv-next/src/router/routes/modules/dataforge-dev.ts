import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/develop/dev/detail/:id',
    name: 'DataforgeDevTaskDetail',
    component: () => import('#/views/dataforge/dev/detail/index.vue'),
    beforeEnter: (to) => {
      const mode = to.query.workloadMode;
      if (mode === 'STREAMING') {
        to.meta.activePath = '/develop/dev/realtime';
        to.meta.title = '实时任务详情';
      } else if (mode === 'BATCH') {
        to.meta.activePath = '/develop/dev/offline';
        to.meta.title = '离线任务详情';
      } else {
        to.meta.activePath = '/develop/dev/workbench';
        to.meta.title = '数据开发详情';
      }
    },
    meta: {
      title: '数据开发详情',
      hideInMenu: true,
      activePath: '/develop/dev/workbench',
    },
  },
];

export default routes;
