import type { App } from 'vue';

import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import { createApp, h, nextTick, reactive, ref } from 'vue';

import { message, Modal } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/modeling/model-materialized';

import RecoveryModal from './recovery-modal.vue';

const harness = vi.hoisted(() => ({ access: { accessToken: '1', refreshToken: '' } }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('#/api/modeling/model-materialized', () => ({
  getModelMaterialized: vi.fn(),
  reconcileModelMaterialized: vi.fn(),
}));

const id = '9007199254740993127';
const row: ModelMaterializedApi.Materialized = {
  id,
  modelId: '9007199254740993123',
  attemptId: '9007199254740993129',
  publishStatus: 'UNKNOWN',
};
const modal = ref<InstanceType<typeof RecoveryModal>>();
const refresh = vi.fn();
let app: App | undefined;
let host: HTMLDivElement;

async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await nextTick();
}
function dialog() {
  return document.querySelector<HTMLDivElement>('.ant-modal')!;
}
function confirm() {
  const button = [...dialog().querySelectorAll<HTMLButtonElement>('button')]
    .find((item) => item.textContent?.replaceAll(/\s/g, '') === '确认核验');
  expect(button).toBeDefined();
  button!.click();
}
async function mount() {
  host = document.createElement('div'); document.body.append(host);
  app = createApp({ setup: () => () => h(RecoveryModal, { ref: modal, onRefresh: refresh }) });
  app.config.errorHandler = () => {};
  app.mount(host); await flush();
}
async function open() {
  await mount(); await modal.value!.open(row); await flush();
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: '' });
  vi.mocked(api.getModelMaterialized).mockResolvedValue(row);
  vi.mocked(api.reconcileModelMaterialized).mockResolvedValue('UNKNOWN');
  vi.spyOn(message, 'info').mockImplementation(() => undefined as never);
});
afterEach(async () => {
  Modal.destroyAll(); app?.unmount(); app = undefined; await flush();
  await vi.waitUntil(() => document.querySelector('.ant-modal-root') === null);
  host?.remove(); vi.restoreAllMocks();
});

describe('materialized recovery with actual Antdv Next controls', () => {
  it('fetches fresh details and does not recover an already settled record', async () => {
    vi.mocked(api.getModelMaterialized).mockResolvedValue({ ...row, publishStatus: 'SUCCESS' });
    await open();
    expect(api.getModelMaterialized).toHaveBeenCalledWith(id);
    expect(api.reconcileModelMaterialized).not.toHaveBeenCalled();
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('submits reconciliation once and leaves an unknown outcome explicit', async () => {
    let finish!: (status: 'UNKNOWN') => void;
    vi.mocked(api.reconcileModelMaterialized).mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    await open();
    expect(dialog().textContent).toContain('当前状态：结果未知');
    confirm(); confirm(); await flush();
    expect(api.reconcileModelMaterialized).toHaveBeenCalledExactlyOnceWith(id);
    expect(dialog().querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary')!.disabled).toBe(true);
    finish('UNKNOWN'); await flush();
    expect(message.info).toHaveBeenCalledWith('外部结果仍无法确认，保持结果未知；请继续核查，勿重新发布');
    expect(refresh).toHaveBeenCalledOnce();
  });

  it('keeps only the latest details and never unlocks from an older response', async () => {
    let finishFirst!: (record: ModelMaterializedApi.Materialized) => void;
    let finishSecond!: (record: ModelMaterializedApi.Materialized) => void;
    vi.mocked(api.getModelMaterialized)
      .mockReturnValueOnce(new Promise((resolve) => { finishFirst = resolve; }))
      .mockReturnValueOnce(new Promise((resolve) => { finishSecond = resolve; }));
    const latest = { ...row, id: '9007199254740993131', publishStatus: 'RUNNING' };
    await mount();
    const firstRequest = modal.value!.open(row);
    const secondRequest = modal.value!.open(latest);
    await flush();
    finishFirst({ ...row, publishStatus: 'SUCCESS' });
    await firstRequest; await flush();
    expect(message.info).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
    expect(dialog().querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary')!.disabled).toBe(true);

    finishSecond(latest); await secondRequest; await flush();
    expect(dialog().textContent).toContain('当前状态：执行中');
    confirm(); await flush();
    expect(api.reconcileModelMaterialized).toHaveBeenCalledExactlyOnceWith(latest.id);
  });

  it('unlocks and refreshes after a failed request so the operation can be checked again', async () => {
    vi.mocked(api.reconcileModelMaterialized).mockRejectedValueOnce(new Error('测试网络失败'));
    await open(); confirm(); await flush();
    expect(dialog().querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary')!.disabled).toBe(false);
    expect(refresh).toHaveBeenCalledOnce();
    confirm(); await flush();
    expect(api.reconcileModelMaterialized).toHaveBeenCalledTimes(2);
  });


  it('does not display or refresh a response that belongs to the previous session', async () => {
    let finish!: (status: 'UNKNOWN') => void;
    vi.mocked(api.reconcileModelMaterialized).mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    await open(); confirm(); await flush();
    harness.access.refreshToken = '2'; await flush();
    finish('UNKNOWN'); await flush();
    expect(message.info).not.toHaveBeenCalled();
    expect(refresh).not.toHaveBeenCalled();
  });
});
