/* eslint-disable vue/one-component-per-file -- 使用轻量控件替身验证上线状态与修订号的跨接口顺序。 */
import type { App } from 'vue';

import type { JobApi } from '#/api/dataforge/job';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { getJob, updateJob, updateJobStatus } from '#/api/dataforge/job';

import JobForm from './job-form.vue';

vi.mock('@vben/stores', () => ({ useAccessStore: () => ({ accessToken: 'session', refreshToken: 'refresh' }) }));
vi.mock('#/api/dataforge/job', () => ({
  createJob: vi.fn(),
  getJob: vi.fn(),
  getJobCategories: vi.fn(async () => []),
  getJobOwners: vi.fn(async () => []),
  previewJobCron: vi.fn(async () => []),
  updateJob: vi.fn(async () => true),
  updateJobStatus: vi.fn(async () => true),
}));
vi.mock('#/components/cron-tab', () => ({ CronTab: defineComponent({ setup: () => () => h('div') }) }));
vi.mock('antdv-next', () => {
  const Container = defineComponent({ setup: (_props, { slots }) => () => h('div', slots.default?.()) });
  const Field = defineComponent({
    props: { value: { type: [String, Number], default: '' }, disabled: Boolean },
    setup: (props) => () => h('input', { value: props.value, disabled: props.disabled }),
  });
  return {
    Alert: Container, Input: Field, Select: Field, Spin: Container, TextArea: Field, TreeSelect: Field, Radio: Container,
    RadioGroup: defineComponent({
      emits: ['update:value'],
      setup: (_props, { emit }) => () => h('button', { type: 'button', 'data-offline': true, onClick: () => emit('update:value', 0) }, '选择下线'),
    }),
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup: (props, { slots }) => () => h('button', { type: 'button', disabled: props.disabled || props.loading }, slots.default?.()),
    }),
    Modal: defineComponent({ setup: (_props, { slots }) => () => h('section', [slots.default?.(), slots.footer?.()]) }),
    message: { success: vi.fn() },
  };
});

let app: App | undefined;
let host: HTMLDivElement | undefined;
afterEach(() => { app?.unmount(); host?.remove(); vi.clearAllMocks(); });
async function flush() { for (let index = 0; index < 10; index++) await Promise.resolve(); await nextTick(); }

describe('作业上线状态编辑', () => {
  it('先用原修订号下线，再使用下线后的修订号保存配置', async () => {
    const job: JobApi.Job = { id: '9007199254740993', revision: 9, categoryId: '9007199254740995', ownerId: '9007199254740997', name: '上线作业', strategy: 'SERIAL_WAIT', status: 1, scheduleStatus: 0, flowJson: '{"nodes":[],"edges":[]}' };
    vi.mocked(getJob).mockResolvedValueOnce({ ...job, revision: 10, status: 0 }).mockResolvedValueOnce({ ...job, revision: 11, status: 0 });
    host = document.createElement('div'); document.body.append(host);
    const saved = vi.fn();
    app = createApp({ setup: () => () => h(JobForm, { open: true, initialJob: job, onSuccess: saved }) });
    app.mount(host); await flush();
    const save = [...host.querySelectorAll('button')].find(button => button.textContent?.trim() === '保存')!;
    expect(save.disabled).toBe(true);
    host.querySelector<HTMLButtonElement>('[data-offline]')!.click(); await flush();
    expect(save.disabled).toBe(false); save.click(); await flush();
    expect(updateJobStatus).toHaveBeenCalledExactlyOnceWith({ id: job.id, revision: 9, status: 0 });
    expect(updateJob).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ id: job.id, revision: 10 }));
    expect(vi.mocked(updateJobStatus).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(updateJob).mock.invocationCallOrder[0]!);
    expect(saved).toHaveBeenCalledWith(expect.objectContaining({ revision: 11, status: 0 }));
  });
});
