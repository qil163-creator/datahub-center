import type { RouteRecordRaw } from 'vue-router';

// 在线填报（数据研发 → 数据集成）：正式菜单由后端配置，这里只挂不进菜单的工作台类页面
const routes: RouteRecordRaw[] = [
  {
    path: '/dataforge/fill',
    name: 'DataforgeFill',
    meta: {
      hideInMenu: true,
      title: '在线填报',
    },
    children: [
      {
        path: 'workbench',
        name: 'DataforgeFillWorkbench',
        component: () => import('#/views/dataforge/fill/workbench/index.vue'),
        meta: {
          title: '填报工作台',
        },
      },
    ],
  },
];

export default routes;
