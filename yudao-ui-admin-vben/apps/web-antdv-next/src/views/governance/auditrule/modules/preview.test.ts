/* eslint-disable vue/one-component-per-file -- 隔离基础控件，验证真实数据源规则不会生成虚构SQL。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { getAuditStrategyList, previewAuditRuleSql } from '#/api/governance/auditrule';

import Preview from './preview.vue';

vi.mock('@vben/icons', () => ({ IconifyIcon: defineComponent({ setup: () => () => h('span') }) }));
vi.mock('#/api/governance/auditrule', () => ({ getAuditStrategyList: vi.fn(), previewAuditRuleSql: vi.fn() }));
vi.mock('antdv-next', () => {
  const container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return { Modal: defineComponent({ props: { open: Boolean }, setup: (props, { slots }) => () => props.open ? h('section', slots.default?.()) : null }),
    Alert: defineComponent({ props: { message: { type: String, default: '' } }, setup: (props) => () => h('aside', props.message) }),
    Button: container, Form: container, FormItem: container, Input: container, Select: container, TextArea: container };
});
let app: App | undefined;
let host: HTMLDivElement | undefined;
afterEach(() => { app?.unmount(); host?.remove(); vi.clearAllMocks(); });
describe('需要真实数据源的稽查预览', () => {
  it.each(['MODEL_STRUCTURE_VALIDATION', 'COLUMN_METADATA_COMPLETENESS', 'RECORD_COMPLETENESS_VALIDATION'])('%s引导到质量任务抽查', async (strategyKey) => {
    const preview = ref<InstanceType<typeof Preview>>();
    host = document.createElement('div'); document.body.append(host);
    app = createApp({ render: () => h(Preview, { ref: preview }) }); app.mount(host);
    await preview.value!.open({ id: 1, name: '需要真实表结构', strategyKey }); await nextTick();
    expect(host.textContent).toContain('质量任务');
    expect(host.textContent).not.toContain('生成 SQL');
    expect(getAuditStrategyList).not.toHaveBeenCalled();
    expect(previewAuditRuleSql).not.toHaveBeenCalled();
  });
});
