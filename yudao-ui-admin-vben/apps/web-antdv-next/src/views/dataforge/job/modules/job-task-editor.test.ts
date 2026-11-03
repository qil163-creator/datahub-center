/* eslint-disable vue/one-component-per-file -- 使用控件替身验证节点作用域与保存协议。 */
import type { App } from 'vue';

import type { IntegrationApi } from '#/api/dataforge/integration';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { getIntegration, saveIntegrationFlow, updateIntegration } from '#/api/dataforge/integration';

import { defaultTask } from '../../integration/model';
import JobTaskEditor from './job-task-editor.vue';

vi.mock('vue-router', () => ({ useRouter: () => ({ resolve: () => ({ href: '/' }) }) }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }) }));
vi.mock('#/api/dataforge/dev', () => ({ getDevTask: vi.fn(), getDevDirList: vi.fn(async () => []) }));
vi.mock('#/api/dataforge/integration/component-capabilities', () => ({ getIntegrationComponentCapabilities: vi.fn(async () => undefined) }));
vi.mock('#/api/dataforge/integration', () => ({
  getIntegration: vi.fn(), getIntegrationCategories: vi.fn(async () => []), getIntegrationOwners: vi.fn(async () => []),
  resolveIntegrationSchema: vi.fn(), saveIntegrationFlow: vi.fn(async () => ({ revision: 4 })), updateIntegration: vi.fn(),
}));
vi.mock('../../dev/modules/task-detail-view.vue', () => ({ default: defineComponent({ render: () => null }) }));
vi.mock('../../dev/modules/task-form.vue', () => ({ default: defineComponent({ render: () => null }) }));
vi.mock('../../integration/reference-theme', () => ({ integrationReferenceTheme: {} }));
vi.mock('../../integration/modules/node-configuration-editor.vue', () => ({ default: defineComponent({
  props: ['config', 'toolCode', 'componentCode', 'disabled'],
  setup(props, { expose }) {
    expose({ validate: () => true });
    return () => h('button', { 'data-component': props.componentCode, disabled: props.disabled, onClick: () => { const draft = props.config; draft.encoding = 'GB18030'; } }, props.toolCode);
  },
}) }));
vi.mock('antdv-next', () => {
  const Container = defineComponent({ setup: (_props, { slots }) => () => h('div', slots.default?.()) });
  const Field = defineComponent({
    props: ['value', 'disabled'], emits: ['update:value'],
    setup: (props, { emit }) => () => h('input', { value: props.value, disabled: props.disabled, onChange: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value) }),
  });
  return { Alert: Container, ConfigProvider: Container, Form: Container, Spin: Container, Input: Field, Select: Field, TextArea: Field, TreeSelect: Field,
    Button: defineComponent({ props: ['disabled', 'loading'], setup: (props, { slots }) => () => h('button', { disabled: props.disabled || props.loading }, slots.default?.()) }),
    Modal: defineComponent({ setup: (_props, { slots }) => () => h('section', [slots.default?.(), slots.footer?.()]) }),
    message: { success: vi.fn() },
  };
});
let app: App | undefined;
let host: HTMLDivElement;
const node = { id: 'job-node-a', kind: 'INTEGRATION' as const, taskId: '9007199254740993', name: '文件集成', x: 0, y: 0, priority: 'MEDIUM' as const };
const flow = { nodes: [
  { id: 'read', type: 'integration-node', x: 0, y: 0, properties: { componentCode: 'read-csv', toolCode: 'read-csv', name: 'CSV', nodeJson: { encoding: 'utf8', fileId: '9007199254740995' } } },
  { id: 'dedup', type: 'integration-node', x: 200, y: 0, properties: { componentCode: 'dedup-records', toolCode: 'data-clean', name: '去重', nodeJson: { fields: ['id'] } } },
], edges: [{ sourceNodeId: 'read', targetNodeId: 'dedup' }] };
function task(status: number): IntegrationApi.Task {
  return { ...defaultTask('OFFLINE'), id: node.taskId, revision: 3, categoryId: '1', ownerId: '1', name: node.name, status, scheduleStatus: 0, flowJson: JSON.stringify(flow) } as IntegrationApi.Task;
}
async function flush() { for (let i = 0; i < 12; i++) await Promise.resolve(); await nextTick(); }
async function mount(status: number, readonly = false) {
  vi.mocked(getIntegration).mockResolvedValue(task(status));
  host = document.createElement('div'); document.body.append(host);
  const saved = vi.fn();
  app = createApp({ setup: () => () => h(JobTaskEditor, { node, open: true, readonly, onSaved: saved }) });
  app.mount(host); await flush(); return saved;
}
function save() { [...host.querySelectorAll('button')].find(button => button.textContent?.trim() === '保存')?.click(); }
afterEach(() => { app?.unmount(); host?.remove(); vi.clearAllMocks(); });

describe('作业内的节点配置', () => {
  it('源任务已上线时仍可保存本作业优先级，不改写源任务或流程', async () => {
    const saved = await mount(1);
    const priority = host.querySelector<HTMLInputElement>('[aria-label="作业节点任务优先级"]')!;
    expect(priority.disabled).toBe(false); priority.value = 'HIGHEST'; priority.dispatchEvent(new Event('change')); await flush(); save(); await flush();
    expect(saved).toHaveBeenCalledWith(expect.objectContaining({ id: node.id, priority: 'HIGHEST' }));
    expect(updateIntegration).not.toHaveBeenCalled(); expect(saveIntegrationFlow).not.toHaveBeenCalled();
  });
  it('文件和别名转换组件可直接编辑，保存时保留完整流程和无损文件编号', async () => {
    await mount(0);
    expect(host.querySelector<HTMLButtonElement>('[data-component="read-csv"]')!.disabled).toBe(true);
    [...host.querySelectorAll('button')].find(button => button.textContent?.trim() === '修改任务配置')!.click(); await flush();
    expect(host.querySelector('[data-component="dedup-records"]')).not.toBeNull();
    host.querySelector<HTMLButtonElement>('[data-component="read-csv"]')!.click(); await flush(); save(); await flush();
    expect(saveIntegrationFlow).toHaveBeenCalledOnce();
    const payload = vi.mocked(saveIntegrationFlow).mock.calls[0]![0];
    expect(payload.id).toBe(node.taskId); expect(payload.revision).toBe(3);
    const stored = JSON.parse(payload.flowJson);
    expect(stored.nodes[0].properties.nodeJson).toEqual({ encoding: 'GB18030', fileId: '9007199254740995' });
    expect(stored.nodes[1]).toEqual(flow.nodes[1]); expect(stored.edges).toEqual(flow.edges);
  });
  it('只读详情不开放节点或源任务写操作', async () => {
    const saved = await mount(0, true);
    expect(host.querySelector<HTMLInputElement>('[aria-label="作业节点任务优先级"]')!.disabled).toBe(true);
    save(); await flush(); expect(saved).not.toHaveBeenCalled(); expect(saveIntegrationFlow).not.toHaveBeenCalled();
  });
});
