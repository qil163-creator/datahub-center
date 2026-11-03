import { theme } from 'antdv-next';

/** 参考业务区域固定为浅色，应用导航仍由项目主题控制。 */
export const integrationReferenceTheme = {
  algorithm: theme.defaultAlgorithm,
  inherit: false,
  token: {
    borderRadius: 2,
    colorPrimary: '#2f62ff',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',
    colorText: '#333333',
    colorTextSecondary: '#999ca3',
    colorTextDisabled: '#a8abb2',
    colorTextPlaceholder: '#a8abb2',
    colorBgContainerDisabled: '#f5f7fa',
    colorIcon: '#606266',
    colorIconHover: '#333333',
    colorBorder: '#e2e5ed',
    controlHeight: 32,
  },
};
