import { initPreferences } from '@vben/preferences';
import '@vben/styles';
import '@vben/styles/antdv-next';

await initPreferences({ namespace: 'database-detail-local-preview', overrides: { app: { locale: 'zh-CN' }, theme: { mode: 'light' } } });
const { createApp } = await import('vue');
const { createRouter, createMemoryHistory } = await import('vue-router');
const { initStores, useAccessStore } = await import('@vben/stores');
const { initComponentAdapter } = await import('./src/adapter/component');
const { initSetupVbenForm } = await import('./src/adapter/form');
const { setupI18n } = await import('./src/locales');
const { registerAccessDirective } = await import('@vben/access');
const { registerLoadingDirective } = await import('@vben/common-ui/es/loading');
await initComponentAdapter();
await initSetupVbenForm();
const { default: App } = await import('./src/app.vue');
const app = createApp(App);
await setupI18n(app);
await initStores(app, { namespace: 'database-detail-local-preview' });
useAccessStore().setAccessCodes(['dataforge:database:query', 'dataforge:database:update']);
useAccessStore().setTenantId(1);
registerAccessDirective(app);
registerLoadingDirective(app, { loading: 'loading', spinning: 'spinning' });
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/detail/:id', component: () => import('./src/views/dataforge/database/detail/index.vue') }] });
app.use(router);
await router.push('/detail/9');
await router.isReady();
app.mount('#app');

const { unmountGlobalLoading } = await import('@vben/utils');
unmountGlobalLoading();

