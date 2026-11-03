import type { App, PropType } from 'vue';

import type { EtlFileOutput } from '#/api/dataforge/etl/file-output';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl/file-output';

import FileOutputs from './file-outputs.vue';

const context = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: '' },
  user: { userInfo: { userId: '7' } },
  denied: [] as string[],
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => context.access,
  useUserStore: () => context.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.every((code) => !context.denied.includes(code)),
  }),
}));
vi.mock('#/api/dataforge/etl/file-output', () => ({
  downloadEtlFileOutput: vi.fn(),
  getEtlFileOutputs: vi.fn(),
  retryEtlFileOutput: vi.fn(),
  retireEtlFileOutput: vi.fn(),
}));
const saveBlob = vi.hoisted(() => vi.fn());
vi.mock('@vben/utils', () => ({
  downloadFileFromBlob: saveBlob,
  formatDateTime: String,
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({
    props: { message: String },
    setup: (props) => () => h('p', props.message),
  }),
  Button: defineComponent({
    setup:
      (_, { attrs, slots }) =>
      () =>
        h('button', attrs, slots.default?.()),
  }),
  Drawer: defineComponent({
    props: { open: Boolean },
    setup:
      (props, { slots }) =>
      () =>
        props.open ? h('section', slots.default?.()) : null,
  }),
  Modal: defineComponent({
    props: { open: Boolean, okButtonProps: Object, cancelButtonProps: Object },
    emits: ['ok', 'cancel'],
    setup:
      (props, { emit, slots }) =>
      () =>
        props.open
          ? h('aside', [
              slots.default?.(),
              h(
                'button',
                {
                  disabled: props.okButtonProps?.disabled,
                  onClick: () => emit('ok'),
                },
                '确认申请',
              ),
              h(
                'button',
                {
                  disabled: props.cancelButtonProps?.disabled,
                  onClick: () => emit('cancel'),
                },
                '取消',
              ),
            ])
          : null,
  }),
  Select: defineComponent({
    props: {
      value: Number,
      options: Array as PropType<{ label: string; value: number }[]>,
      disabled: Boolean,
    },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'select',
          {
            value: props.value,
            disabled: props.disabled,
            onChange: (event: Event) =>
              emit(
                'update:value',
                Number((event.target as HTMLSelectElement).value),
              ),
          },
          props.options?.map((option) =>
            h('option', { value: option.value }, option.label),
          ),
        ),
  }),
  TextArea: defineComponent({
    props: { value: String },
    emits: ['update:value'],
    setup:
      (props, { emit }) =>
      () =>
        h('textarea', {
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLTextAreaElement).value),
        }),
  }),
  Table: defineComponent({
    props: {
      dataSource: {
        type: Array as PropType<EtlFileOutput[]>,
        default: () => [],
      },
    },
    setup:
      (props, { slots }) =>
      () =>
        h(
          'div',
          props.dataSource.map((record) =>
            h('article', [
              record.fileName,
              slots.bodyCell?.({ column: { key: 'retentionStatus' }, record }),
              slots.bodyCell?.({ column: { key: 'retireAfter' }, record }),
              slots.bodyCell?.({ column: { key: 'actions' }, record }),
            ]),
          ),
        ),
  }),
}));

const item: EtlFileOutput = {
  id: '9007199254740993',
  recordId: '9007199254740995',
  nodeNo: 'out',
  storageKey: 'exports',
  fileName: 'result.json',
  state: 'MANUAL',
  recoveryStatus: 'MANUAL',
  attempts: 10,
  revision: '9007199254740997',
};
let app: App;
let host: HTMLDivElement;
let view: {
  close: () => void;
  open: (id: EtlFileOutput['recordId']) => Promise<void>;
};
async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
beforeEach(() => {
  vi.resetAllMocks();
  context.access = reactive({ accessToken: '1', refreshToken: '' });
  context.user = reactive({ userInfo: { userId: '7' } });
  context.denied = reactive([]);
  vi.mocked(api.getEtlFileOutputs).mockResolvedValue([item]);
  vi.mocked(api.downloadEtlFileOutput).mockResolvedValue(new Blob(['file']));
  vi.mocked(api.retryEtlFileOutput).mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(FileOutputs);
  view = app.mount(host) as unknown as typeof view;
});
afterEach(() => {
  app.unmount();
  host.remove();
  vi.restoreAllMocks();
});

const kept: EtlFileOutput = {
  ...item,
  state: 'PUBLISHED',
  recoveryStatus: 'DONE',
  retentionStatus: 'KEEP',
};
const requested: EtlFileOutput = {
  ...kept,
  retentionStatus: 'REQUESTED',
  recoveryStatus: 'PENDING',
  revision: '9007199254740998',
};
function button(text: string) {
  return [...host.querySelectorAll('button')].find(
    (candidate) => candidate.textContent?.trim() === text,
  );
}
async function requestCleanup() {
  vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([kept]);
  await view.open(kept.recordId);
  await flush();
  button('申请清理')!.click();
  await flush();
}
async function fillCleanupReason() {
  const input = host.querySelector('aside textarea')! as HTMLTextAreaElement;
  input.value = '  已完成归档，停止提供下载  ';
  input.dispatchEvent(new Event('input'));
  await flush();
}

it('申请清理需要明确确认及原因，传输无损修订号和保留期限并刷新真实回执', async () => {
  const now = 1_800_000_000_000;
  vi.spyOn(Date, 'now').mockReturnValue(now);
  vi.mocked(api.retireEtlFileOutput).mockResolvedValueOnce(requested);
  await requestCleanup();
  expect(button('确认申请')?.disabled).toBe(true);
  expect(api.retireEtlFileOutput).not.toHaveBeenCalled();
  expect(host.textContent).toContain('清理后无法恢复');
  await fillCleanupReason();
  vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([requested]);
  button('确认申请')!.click();
  await flush();
  expect(api.retireEtlFileOutput).toHaveBeenCalledWith({
    id: '9007199254740993',
    expectedRevision: '9007199254740997',
    confirmDeletion: true,
    reason: '已完成归档，停止提供下载',
    retireAfter: now + 7 * 86_400_000,
  });
  expect(host.querySelector('aside')).toBeNull();
  expect(host.textContent).toContain('已申请清理');
  expect(button('下载')).toBeUndefined();
  expect(api.getEtlFileOutputs).toHaveBeenCalledTimes(2);
});

it.each(['REQUESTED', 'PURGED', null] as const)(
  '清理状态 %s 不提供再次申请，旧回执仍可正常下载',
  async (retentionStatus) => {
    vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([
      { ...kept, retentionStatus },
    ]);
    await view.open(kept.recordId);
    await flush();
    expect(button('申请清理')).toBeUndefined();
    expect(Boolean(button('下载'))).toBe(retentionStatus === null);
  },
);

it.each(['dataforge:etl:query', 'dataforge:etl:file-cleanup'])(
  '缺少权限 %s 不展示清理入口',
  async (code) => {
    context.denied.push(code);
    vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([kept]);
    await view.open(kept.recordId);
    await flush();
    expect(button('申请清理')).toBeUndefined();
  },
);

it('清理权限撤销立即关闭确认窗口并阻止提交', async () => {
  await requestCleanup();
  await fillCleanupReason();
  context.denied.push('dataforge:etl:file-cleanup');
  await flush();
  expect(host.querySelector('aside')).toBeNull();
  expect(button('申请清理')).toBeUndefined();
  expect(api.retireEtlFileOutput).not.toHaveBeenCalled();
});

it('清理请求中的重复点击被阻止，网络失败只显示结果待核对', async () => {
  let fail!: (reason: Error) => void;
  vi.mocked(api.retireEtlFileOutput).mockReturnValueOnce(
    new Promise((_, reject) => {
      fail = reject;
    }),
  );
  await requestCleanup();
  await fillCleanupReason();
  const confirm = button('确认申请')!;
  confirm.click();
  confirm.click();
  await flush();
  expect(api.retireEtlFileOutput).toHaveBeenCalledTimes(1);
  expect(button('取消')?.disabled).toBe(true);
  fail(new Error('lost ACK'));
  await flush();
  expect(host.textContent).toContain('清理申请未确认');
  expect(host.querySelector('article')?.textContent).not.toContain('已清理');
  expect(button('确认申请')?.disabled).toBe(false);
  expect(api.getEtlFileOutputs).toHaveBeenCalledTimes(1);
});

it('不把其他文件或未受理状态的响应视为本次清理成功', async () => {
  vi.mocked(api.retireEtlFileOutput).mockResolvedValueOnce({
    ...requested,
    id: 'another',
  });
  await requestCleanup();
  await fillCleanupReason();
  button('确认申请')!.click();
  await flush();
  expect(host.querySelector('aside')).not.toBeNull();
  expect(host.textContent).toContain('清理申请未确认');
  expect(api.getEtlFileOutputs).toHaveBeenCalledTimes(1);
});

it('切换登录会话后迟到的清理回执不会刷新或关闭新窗口', async () => {
  let complete!: (result: EtlFileOutput) => void;
  vi.mocked(api.retireEtlFileOutput).mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve;
    }),
  );
  await requestCleanup();
  await fillCleanupReason();
  button('确认申请')!.click();
  await flush();
  context.access.refreshToken = '2';
  await flush();
  vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([
    { ...kept, recordId: '22', fileName: 'new.csv' },
  ]);
  await view.open('22');
  await flush();
  complete(requested);
  await flush();
  expect(host.textContent).toContain('new.csv');
  expect(api.getEtlFileOutputs).toHaveBeenCalledTimes(2);
});

it('清理对账只要求查询和清理权限，普通文件对账仍要求运行权限', async () => {
  context.denied.push('dataforge:etl:run');
  vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([
    { ...requested, recoveryStatus: 'MANUAL' },
    { ...item, id: '2', retentionStatus: 'KEEP' },
  ]);
  await view.open(kept.recordId);
  await flush();
  expect(
    [...host.querySelectorAll('button')].filter(
      (candidate) => candidate.textContent?.trim() === '重新对账',
    ),
  ).toHaveLength(1);
  context.denied.push('dataforge:etl:file-cleanup');
  await flush();
  expect(button('重新对账')).toBeUndefined();
});

it('切换登录会话清空回执，拒绝旧会话的迟到结果', async () => {
  let complete!: (rows: EtlFileOutput[]) => void;
  vi.mocked(api.getEtlFileOutputs).mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve;
    }),
  );
  const pending = view.open(item.recordId as string);
  await flush();
  context.access.refreshToken = '2';
  await flush();
  complete([item]);
  await pending;
  await flush();
  expect(host.textContent).not.toContain('result.json');
  expect(api.getEtlFileOutputs).toHaveBeenCalledWith('9007199254740995');
});

it('列表响应完成的同一时刻撤销查询权限，不显示未获准回执', async () => {
  let complete!: (rows: EtlFileOutput[]) => void;
  vi.mocked(api.getEtlFileOutputs).mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve;
    }),
  );
  const pending = view.open(item.recordId);
  await flush();
  complete([kept]);
  context.denied.push('dataforge:etl:query');
  await pending;
  await flush();
  expect(host.textContent).not.toContain('result.json');
});

it.each(['actor', 'permission'] as const)(
  '对账响应同一时刻变更 %s，不刷新旧记录',
  async (change) => {
    let complete!: (accepted: boolean) => void;
    vi.mocked(api.retryEtlFileOutput).mockReturnValueOnce(
      new Promise((resolve) => {
        complete = resolve;
      }),
    );
    await view.open(item.recordId);
    await flush();
    const input = host.querySelector('textarea')!;
    input.value = '核验原运行已退出';
    input.dispatchEvent(new Event('input'));
    await flush();
    button('重新对账')!.click();
    await flush();
    complete(true);
    if (change === 'actor') context.access.refreshToken = '2';
    else context.denied.push('dataforge:etl:run');
    await flush();
    expect(api.getEtlFileOutputs).toHaveBeenCalledTimes(1);
  },
);

it('必须填写原因并无损传输文件编号与修订号，受理后重新读取回执', async () => {
  await view.open(item.recordId as string);
  await flush();
  const retry = [...host.querySelectorAll('button')].find(
    (button) => button.textContent?.trim() === '重新对账',
  )!;
  retry.click();
  await flush();
  expect(api.retryEtlFileOutput).not.toHaveBeenCalled();
  const input = host.querySelector('textarea')!;
  input.value = '  核验原运行已退出  ';
  input.dispatchEvent(new Event('input'));
  await flush();
  retry.click();
  await flush();
  expect(api.retryEtlFileOutput).toHaveBeenCalledWith({
    id: '9007199254740993',
    expectedRevision: '9007199254740997',
    reason: '核验原运行已退出',
  });
  expect(api.getEtlFileOutputs).toHaveBeenCalledTimes(2);
});

it('已完成回执没有重新对账按钮，旧加载失败不覆盖新窗口', async () => {
  let fail!: (reason: Error) => void;
  vi.mocked(api.getEtlFileOutputs).mockReturnValueOnce(
    new Promise((_, reject) => {
      fail = reject;
    }),
  );
  const old = view.open('10');
  await flush();
  vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([
    { ...item, recoveryStatus: 'DONE' },
  ]);
  await view.open('11');
  fail(new Error('old'));
  await old;
  await flush();
  expect(host.textContent).toContain('result.json');
  expect(host.textContent).not.toContain('加载失败');
  expect(
    [...host.querySelectorAll('button')].some(
      (button) => button.textContent?.trim() === '重新对账',
    ),
  ).toBe(false);
});

it('仅下载已发布且完成的回执，文件名去除路径与换行字符', async () => {
  const ready = {
    ...item,
    fileName: '../report\r\nX-Injected: yes.csv',
    state: 'PUBLISHED',
    recoveryStatus: 'DONE',
  };
  vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([ready]);
  const blob = new Blob(['ready']);
  vi.mocked(api.downloadEtlFileOutput).mockResolvedValueOnce(blob);
  await view.open(ready.recordId);
  await flush();

  const download = [...host.querySelectorAll('button')].find(
    (button) => button.textContent?.trim() === '下载',
  )!;
  download.click();
  await flush();

  expect(api.downloadEtlFileOutput).toHaveBeenCalledWith(ready.id);
  expect(saveBlob).toHaveBeenCalledWith({
    fileName: '_report__X-Injected_ yes.csv',
    source: blob,
  });
});

it('下载拒绝响应不创建浏览器下载', async () => {
  vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([
    { ...item, state: 'PUBLISHED', recoveryStatus: 'DONE' },
  ]);
  vi.mocked(api.downloadEtlFileOutput).mockRejectedValueOnce(
    new Error('业务拒绝'),
  );
  await view.open(item.recordId);
  await flush();
  [...host.querySelectorAll('button')]
    .find((button) => button.textContent?.trim() === '下载')
    ?.click();
  await flush();

  expect(saveBlob).not.toHaveBeenCalled();
  expect(host.textContent).toContain('文件下载未完成');
});

it('关闭或切换登录会话后，迟到的下载 Blob 不会触发浏览器下载', async () => {
  vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([
    { ...item, state: 'PUBLISHED', recoveryStatus: 'DONE' },
  ]);
  let complete!: (blob: Blob) => void;
  vi.mocked(api.downloadEtlFileOutput).mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve;
    }),
  );
  await view.open(item.recordId);
  await flush();
  [...host.querySelectorAll('button')]
    .find((button) => button.textContent?.trim() === '下载')!
    .click();
  await flush();
  context.access.refreshToken = 'other';
  await flush();
  complete(new Blob(['late']));
  await flush();

  expect(saveBlob).not.toHaveBeenCalled();
});

it('下载中阻止重复点击，失败后释放锁允许再次尝试', async () => {
  vi.mocked(api.getEtlFileOutputs).mockResolvedValueOnce([
    { ...item, state: 'PUBLISHED', recoveryStatus: 'DONE' },
  ]);
  let fail!: (reason: Error) => void;
  vi.mocked(api.downloadEtlFileOutput).mockReturnValueOnce(
    new Promise((_, reject) => {
      fail = reject;
    }),
  );
  await view.open(item.recordId);
  await flush();
  const button = [...host.querySelectorAll('button')].find(
    (candidate) => candidate.textContent?.trim() === '下载',
  )!;
  button.click();
  button.click();
  await flush();
  expect(api.downloadEtlFileOutput).toHaveBeenCalledTimes(1);

  fail(new Error('network'));
  await flush();
  button.click();
  await flush();
  expect(api.downloadEtlFileOutput).toHaveBeenCalledTimes(2);
  expect(saveBlob).toHaveBeenCalledTimes(1);
});
