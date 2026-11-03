import process from 'node:process';

import { defineConfig } from '@vben/vite-config';

import { loadEnv } from 'vite';

export default defineConfig(async ({ mode }) => {
  const env = loadEnv(mode, process.cwd());
  const gatewayUrl = env.VITE_BASE_URL || 'http://127.0.0.1:48083';
  return {
    application: {},
    vite: {
      server: {
        allowedHosts: true,
        strictPort: true,
        proxy: {
          '/admin-api': {
            changeOrigin: true,
            rewrite: (path) => path.replace(/^\/admin-api/, ''),
            // 本地开发沿用环境中配置的网关地址。
            target: `${gatewayUrl.replace(/\/$/, '')}/admin-api`,
            ws: true,
          },
        },
      },
    },
  };
});
