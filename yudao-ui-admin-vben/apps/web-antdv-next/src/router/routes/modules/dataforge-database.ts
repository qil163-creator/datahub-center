import type { RouteRecordRaw } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/develop/databases/database/detail/:id',
    name: 'DataforgeDatabaseDetail',
    component: () => import('#/views/dataforge/database/detail/index.vue'),
    meta: {
      title: '数据库详情',
      hideInMenu: true,
      activePath: '/develop/database',
    },
  },
];

export default routes;
