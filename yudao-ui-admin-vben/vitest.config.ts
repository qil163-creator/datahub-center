import Vue from '@vitejs/plugin-vue';
import VueJsx from '@vitejs/plugin-vue-jsx';
import { configDefaults, defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [Vue(), VueJsx()],
  publicDir: 'apps/web-antdv-next/public',
  test: {
    environment: 'happy-dom',
    server: {
      deps: {
        // Transform the picker dependency's extensionless Dayjs plugin imports
        // when rendering real Antdv Next controls in component regressions.
        inline: [
          /(?:^|[/\\])antdv-next(?:[/\\]|$)/,
          /(?:^|[/\\])@v-c[/\\]picker(?:[/\\]|$)/,
        ],
      },
    },
    environmentOptions: {
      happyDOM: {
        settings: {
          // happy-dom v20+ disables JS evaluation by default (security fix).
          // Treat disabled script loading as success to preserve test behavior.
          handleDisabledFileLoadingAsSuccess: true,
        },
      },
    },
    exclude: [
      ...configDefaults.exclude,
      '**/e2e/**',
      '**/dist/**',
      '**/.{idea,git,cache,output,temp}/**',
      '**/node_modules/**',
      '**/{stylelint,eslint}.config.*',
      '**/{oxfmt,oxlint}.config.*',
    ],
  },
});
