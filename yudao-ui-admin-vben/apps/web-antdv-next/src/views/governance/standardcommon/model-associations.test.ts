import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

const state = vi.hoisted(() => ({ access: { accessToken: 'token', accessCodes: [] as string[] }, element: vi.fn(), codeset: vi.fn() }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: (codes: string[]) => codes.every((code) => state.access.accessCodes.includes(code)) }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access }));
vi.mock('@vben/utils', () => ({ formatDate: vi.fn() }));
vi.mock('vue-router', () => ({ useRoute: () => ({ fullPath: '/' }), useRouter: () => ({ push: vi.fn() }) }));
vi.mock('#/api/governance/standardelement', () => ({ getStandardElementModelFields: state.element }));
vi.mock('#/api/governance/standardcodeset', () => ({ getStandardCodeSetModelFields: state.codeset }));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn() }));
vi.mock('#/hooks/use-object-request', () => ({ useObjectRequest: () => ({ begin: () => () => true }) }));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({ props: ['message'], setup: (props) => () => h('p', props.message) }),
  Button: defineComponent({ setup: (_, { slots }) => () => h('button', slots.default?.()) }),
  Table: defineComponent({ setup: () => () => h('div', 'table') }),
}));
vi.mock('./standard-toolbar.vue', () => ({ default: defineComponent({ setup: (_, { slots }) => () => h('div', slots.title?.()) }) }));
vi.mock('./standard-pagination.vue', () => ({ default: defineComponent({ setup: () => () => h('div') }) }));
import ModelFieldsGrid from '../standardelement/detail/modules/model-fields-grid.vue';
import ModelAssociations from './model-associations.vue';

describe('model association permissions', () => {
  let dispose: (() => void) | undefined;
  afterEach(() => { dispose?.(); vi.clearAllMocks(); });
  it.each(['element', 'codeset'] as const)('does not request %s models without access', async (kind) => {
    state.access = reactive({ accessToken: 'token', accessCodes: [] as string[] });
    const host = document.createElement('div');
    const app = createApp(ModelAssociations, { kind, ownerId: 1 });
    app.mount(host); dispose = () => app.unmount();
    await nextTick();
    expect(host.textContent).toContain('暂无逻辑模型查询权限');
    expect(state.element).not.toHaveBeenCalled();
    expect(state.codeset).not.toHaveBeenCalled();
  });
  it('does not request design-time fields without model permission', async () => {
    state.access = reactive({ accessToken: 'token', accessCodes: [] as string[] });
    const host = document.createElement('div');
    const app = createApp(ModelFieldsGrid, { elementId: 1 });
    app.mount(host); dispose = () => app.unmount();
    await nextTick();
    expect(host.textContent).toContain('暂无逻辑模型查询权限');
    expect(state.element).not.toHaveBeenCalled();
  });
});
