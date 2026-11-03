import type { App } from 'vue';

import { createApp, h, nextTick, reactive, ref } from 'vue';

import { message, Modal } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as typeApi from '#/api/dataforge/databasetype';
import * as api from '#/api/modeling/model-materialized';

import PublishModal from './publish-modal.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: '' },
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/hooks', () => ({
  getDictOptions: () => [{ value: 'RELATIONAL', label: '关系型数据库' }],
}));
vi.mock('#/api/dataforge/databasetype', () => ({
  getDatabaseTypeSimpleList: vi.fn(),
}));
vi.mock('#/api/modeling/model-materialized', () => ({
  getModelMaterialized: vi.fn(),
  getModelMaterializedDatabases: vi.fn(),
  publishModelMaterialized: vi.fn(),
  republishModelMaterialized: vi.fn(),
}));

const modelId = '9007199254740993123';
const datasourceId = '9007199254740993125';
const newDatasourceId = '9007199254740993126';
const materializedId = '9007199254740993127';
const modal = ref<InstanceType<typeof PublishModal>>();
const success = vi.fn();
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 15; index++) await Promise.resolve();
  await nextTick();
}
function dialog() {
  return document.querySelector<HTMLDivElement>(
    '.ant-modal:not(.ant-modal-confirm)',
  )!;
}
function button(text: string, element: Element = dialog()) {
  return [...element.querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => item.textContent?.replaceAll(/\s/g, '') === text,
  )!;
}
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    setup: () => () => h(PublishModal, { ref: modal, onSuccess: success }),
  });
  app.mount(host);
  await flush();
}
async function openRepublish() {
  await mount();
  await modal.value!.openRepublish({
    id: materializedId,
    modelId,
    datasourceId,
    datasourceName: '原连接',
    publishStatus: 'SUCCESS',
  });
  await flush();
}
async function chooseDatasource(name = '原连接') {
  dialog()
    .querySelector<HTMLButtonElement>('.materialized-database-trigger')!
    .click();
  await flush();
  const panel = document.querySelector('.materialized-database-panel')!;
  expect(panel).not.toBeNull();
  const group = panel.querySelector('[aria-label="数据连接"]')!;
  button(name, group).click();
  await flush();
}
async function confirm() {
  button('确认').click();
  await flush();
  const confirmation = document.querySelector('.ant-modal-confirm')!;
  expect(confirmation).not.toBeNull();
  button('OK', confirmation).click();
  await flush();
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: '' });
  vi.mocked(api.getModelMaterializedDatabases).mockResolvedValue([
    {
      id: datasourceId,
      name: '原连接',
      databaseTypeName: 'PostgreSQL',
      databaseType: '1',
      categoryCode: 'RELATIONAL',
      databaseAddress: 'localhost:5432',
    },
    {
      id: newDatasourceId,
      name: '新连接',
      databaseTypeName: 'PostgreSQL',
      databaseType: '1',
      categoryCode: 'RELATIONAL',
      databaseAddress: 'localhost:5433',
    },
  ]);
  vi.mocked(typeApi.getDatabaseTypeSimpleList).mockResolvedValue([]);
  vi.mocked(api.publishModelMaterialized).mockResolvedValue(materializedId);
  vi.mocked(api.republishModelMaterialized).mockResolvedValue(materializedId);
  vi.mocked(api.getModelMaterialized).mockResolvedValue({
    modelId,
    publishStatus: 'SUCCESS',
  });
  vi.spyOn(message, 'warning').mockImplementation(() => undefined as never);
  vi.spyOn(message, 'success').mockImplementation(() => undefined as never);
});
afterEach(async () => {
  Modal.destroyAll();
  app?.unmount();
  app = undefined;
  await flush();
  await vi.waitUntil(() => document.querySelector('.ant-modal-root') === null);
  host?.remove();
  vi.restoreAllMocks();
});

describe('发布模型弹窗真实控件与调用契约', () => {
  it.each(['RUNNING', 'UNKNOWN'])(
    '阻止 %s 未核验部署再次发布',
    async (publishStatus) => {
      await mount();
      await modal.value!.openRepublish({
        id: materializedId,
        modelId,
        datasourceId,
        publishStatus,
      });
      await flush();
      expect(message.warning).toHaveBeenCalledWith(
        '部署仍在执行或结果未知，请先核验结果',
      );
      expect(api.getModelMaterializedDatabases).not.toHaveBeenCalled();
    },
  );
  it('显示五个参考字段、256 字描述并要求连接', async () => {
    await mount();
    await modal.value!.open(modelId);
    await flush();
    expect(dialog().querySelectorAll('.ant-form-item')).toHaveLength(5);
    expect(dialog().querySelector('textarea')!.maxLength).toBe(256);
    expect(
      dialog().querySelector<HTMLInputElement>('input[type="radio"]')!.checked,
    ).toBe(true);
    button('确认').click();
    await flush();
    expect(message.warning).toHaveBeenCalledWith('请选择数据库连接');
    expect(api.publishModelMaterialized).not.toHaveBeenCalled();
  });
  it('原目标重发前二次确认，防止双击并保留完整记录编号', async () => {
    await openRepublish();
    const disabledInputs = [
      ...dialog().querySelectorAll<HTMLInputElement>('input[disabled]'),
    ].map((input) => input.value);
    expect(disabledInputs).toEqual(
      expect.arrayContaining(['PostgreSQL', 'localhost:5432']),
    );
    button('确认').click();
    button('确认').click();
    await flush();
    expect(document.querySelectorAll('.ant-modal-confirm')).toHaveLength(1);
    expect(api.republishModelMaterialized).not.toHaveBeenCalled();
    expect(document.querySelector('.ant-modal-confirm')!.textContent).toContain(
      '表内数据会丢失',
    );
    button('OK', document.querySelector('.ant-modal-confirm')!).click();
    await flush();
    expect(api.republishModelMaterialized).toHaveBeenCalledExactlyOnceWith({
      id: materializedId,
      publishMode: 'REBUILD',
      description: undefined,
    });
    expect(success).toHaveBeenCalledOnce();
  });
  it('重发可改选新目标，按模型编号调用发布并保持无损 ID', async () => {
    await openRepublish();
    await chooseDatasource('新连接');
    await confirm();
    expect(api.publishModelMaterialized).toHaveBeenCalledExactlyOnceWith({
      modelId,
      datasourceId: newDatasourceId,
      publishMode: 'REBUILD',
      description: undefined,
    });
    expect(api.republishModelMaterialized).not.toHaveBeenCalled();
  });
  it('类型目录失败时仍可使用授权连接并进行增量发布', async () => {
    vi.mocked(typeApi.getDatabaseTypeSimpleList).mockRejectedValue(
      new Error('类型目录不可用'),
    );
    await mount();
    await modal.value!.open(modelId);
    await flush();
    await chooseDatasource();
    [...dialog().querySelectorAll<HTMLElement>('.ant-radio-wrapper')]
      .find((item) => item.textContent?.includes('增量发布'))!
      .click();
    await flush();
    expect(typeApi.getDatabaseTypeSimpleList).toHaveBeenCalledWith(undefined, {
      silentError: true,
    });
    await confirm();
    expect(api.publishModelMaterialized).toHaveBeenCalledWith({
      modelId,
      datasourceId,
      publishMode: 'INCREMENT',
      description: undefined,
    });
  });
  it('批量顺序提交并保留失败项，重试不再提交已成功模型', async () => {
    let finish!: (value: string) => void;
    vi.mocked(api.publishModelMaterialized)
      .mockReturnValueOnce(
        new Promise((resolve) => {
          finish = resolve;
        }),
      )
      .mockRejectedValueOnce(new Error('第二个模型失败'));
    await mount();
    await modal.value!.openBatch([
      { modelId, modelAlias: '模型一' },
      { modelId: '9007199254740993124', modelAlias: '模型二' },
    ]);
    await flush();
    await chooseDatasource();
    await confirm();
    expect(api.publishModelMaterialized).toHaveBeenCalledTimes(1);
    finish(materializedId);
    await flush();
    expect(api.publishModelMaterialized).toHaveBeenCalledTimes(2);
    expect(dialog().textContent).toContain('模型二');
    expect(dialog().textContent).toContain('第二个模型失败');
    await vi.waitUntil(() => document.querySelector('.ant-modal-confirm') === null);
    vi.mocked(api.publishModelMaterialized).mockResolvedValue(materializedId);
    await confirm();
    expect(api.publishModelMaterialized).toHaveBeenCalledTimes(3);
    expect(api.publishModelMaterialized).toHaveBeenLastCalledWith(
      expect.objectContaining({ modelId: '9007199254740993124' }),
    );
  });
  it('用户会话变化关闭弹窗，旧目录响应不能写入下次打开', async () => {
    let finish!: (value: api.ModelMaterializedApi.Database[]) => void;
    vi.mocked(api.getModelMaterializedDatabases).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    await mount();
    const earlier = modal.value!.open(modelId);
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    await vi.waitUntil(() => document.querySelector('.ant-modal-root') === null);
    finish([{ id: datasourceId, name: '过期连接' }]);
    await earlier;
    await modal.value!.open(modelId);
    await flush();
    await chooseDatasource();
    expect(document.body.textContent).not.toContain('过期连接');
  });
});
