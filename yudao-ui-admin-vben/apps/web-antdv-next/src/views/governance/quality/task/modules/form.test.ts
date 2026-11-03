/* eslint-disable vue/one-component-per-file -- 隔离通用控件，验证真实任务表单的删除与提交行为。 */
import type { App } from 'vue';

import type { QualityTaskApi } from '#/api/governance/quality/task';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getQualityTask, updateQualityTask } from '#/api/governance/quality/task';

import TaskForm from './form.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: 'actor-a', refreshToken: '' },
  confirm: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: () => true }) }));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '' }));
vi.mock('#/api/governance/categorydataquality', () => ({ getCategoryDataQualityList: vi.fn(async () => []) }));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn(async () => []) }));
vi.mock('#/api/governance/quality/task', () => ({
  getQualityTask: vi.fn(),
  getQualityTaskRuleCatalog: vi.fn(async () => []),
  suggestQualityTaskRules: vi.fn(async () => []),
  createQualityTask: vi.fn(),
  updateQualityTask: vi.fn(async () => true),
}));
vi.mock('#/components/cron-tab', () => ({ CronTab: () => null }));
vi.mock('./object-editor.vue', () => ({ default: () => null }));
vi.mock('./rule-editor.vue', () => ({ default: () => null }));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup: (_, { slots }) => () => h('div', slots.default?.()),
  });
  return {
    Alert: container,
    Button: defineComponent({
      setup: (_, { attrs, slots }) => () => h('button', attrs, slots.default?.()),
    }),
    Descriptions: container,
    DescriptionsItem: container,
    Form: defineComponent({
      setup(_, { expose, slots }) {
        expose({ validate: async () => undefined });
        return () => h('div', slots.default?.());
      },
    }),
    FormItem: container,
    Input: container,
    InputNumber: container,
    RadioGroup: container,
    Select: container,
    Spin: container,
    TabPane: container,
    Tabs: container,
    TextArea: container,
    Modal: { confirm: harness.confirm },
    message: { warning: harness.warning, success: vi.fn() },
  };
});

const task: QualityTaskApi.Task = {
  id: '9007199254740993',
  taskName: '引用检查任务',
  scheduleType: 'MANUAL',
  objects: [
    { clientKey: 'source', objectName: '源对象', datasourceConnId: 1, tableName: 'source_table' },
    { clientKey: 'reference', objectName: '引用对象', datasourceConnId: 1, tableName: 'reference_table' },
    { clientKey: 'unused', objectName: '未引用对象', datasourceConnId: 1, tableName: 'unused_table' },
  ],
  evaluations: [{
    objectKey: 'source',
    evaluationName: '主数据引用检查',
    strategyKey: 'FOREIGN_KEY_VALIDATION',
    ruleCode: '112',
    qualityDim: 'COMPLETENESS',
    severity: 'CRITICAL',
    status: 0,
    columnNames: ['reference_id'],
    config: { referenceObjectKey: 'reference', refColumn: 'id' },
  }],
};

let app: App;
let host: HTMLDivElement;
async function settle() {
  for (let index = 0; index < 8; index++) await nextTick();
}
function button(label: string) {
  const found = [...host.querySelectorAll('button')].find((entry) => entry.textContent?.trim() === label);
  if (!found) throw new Error(`Button missing: ${label}`);
  return found;
}
function deleteObject(name: string) {
  const row = [...host.querySelectorAll('tbody tr')].find((entry) => entry.textContent?.includes(name));
  const action = [...row!.querySelectorAll('button')].find((entry) => entry.textContent?.trim() === '删除');
  action!.click();
}
beforeEach(async () => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: 'actor-a', refreshToken: '' });
  harness.confirm.mockImplementation((options: { onOk: () => void }) => options.onOk());
  vi.mocked(getQualityTask).mockResolvedValue(JSON.parse(JSON.stringify(task)));
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(TaskForm, { id: '9007199254740993' });
  app.mount(host);
  await settle();
  button('下一步').click();
  await settle();
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('quality task object references', () => {
  it('blocks deleting a referenced object and preserves the rule and objects in the saved payload', async () => {
    deleteObject('引用对象');
    await settle();
    expect(harness.confirm).not.toHaveBeenCalled();
    expect(harness.warning).toHaveBeenCalledWith(expect.stringContaining('请先删除或调整引用规则'));
    expect(host.querySelectorAll('tbody tr')).toHaveLength(3);
    button('下一步').click();
    await settle();
    button('确定并退出').click();
    await settle();
    expect(updateQualityTask).toHaveBeenCalledWith(expect.objectContaining({
      objects: task.objects,
      evaluations: task.evaluations,
    }));
  });
  it('still confirms and deletes an object that no rule references', async () => {
    deleteObject('未引用对象');
    await settle();
    expect(harness.confirm).toHaveBeenCalledTimes(1);
    expect(harness.warning).not.toHaveBeenCalled();
    expect(host.querySelectorAll('tbody tr')).toHaveLength(2);
    button('下一步').click();
    await settle();
    button('确定并退出').click();
    await settle();
    expect(updateQualityTask).toHaveBeenCalledWith(expect.objectContaining({
      objects: task.objects?.slice(0, 2),
      evaluations: task.evaluations,
    }));
  });
});
