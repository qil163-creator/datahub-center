import { defineOverridesPreferences } from '@vben/preferences';

/**
 * @description 项目配置文件
 * 只需要覆盖项目中的一部分配置，不需要的配置不用覆盖，会自动使用默认配置
 * !!! 更改配置后请清空缓存，否则可能不生效
 */
export const overridesPreferences = defineOverridesPreferences({
  // overrides
  app: {
    /** 后端路由模式 */
    accessMode: 'backend',
    /** 首页（登录后默认进入），与 router/routes/modules/home.ts 保持一致 */
    defaultHomePath: '/home',
    name: import.meta.env.VITE_APP_TITLE,
    enableRefreshToken: true,
    /** 项目默认头像，放在 public 下，避免依赖 unpkg 外网资源 */
    defaultAvatar: `${import.meta.env.BASE_URL}avatar.svg`,
  },
  logo: {
    /** 项目自有图标，放在 public 下，避免内网部署时依赖 unpkg 外网资源 */
    source: `${import.meta.env.BASE_URL}logo.svg`,
  },
  footer: {
    /** 默认关闭 footer 页脚，因为有一定遮挡 */
    enable: false,
    fixed: false,
  },
  copyright: {
    companyName: import.meta.env.VITE_APP_TITLE,
    companySiteLink: 'https://gitee.com/yudaocode/yudao-ui-admin-vben',
  },
});
