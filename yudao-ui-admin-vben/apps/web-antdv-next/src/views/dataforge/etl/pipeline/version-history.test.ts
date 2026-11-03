import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl';
import * as compareApi from '#/api/dataforge/etl/version';

import VersionHistory from './version-history.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  user: { userInfo: { userId: '7' } as null | { userId: string } },
  restored: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '2026-10-03' }));
vi.mock('#/api/dataforge/etl', () => ({
  getEtlVersionPage: vi.fn(),
  getEtlVersion: vi.fn(),
  diffEtlVersion: vi.fn(),
  restoreEtlVersion: vi.fn(),
}));
vi.mock('#/api/dataforge/etl/version', () => ({ compareEtlVersions: vi.fn() }));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  const dialog = defineComponent({
    props: { open: Boolean },
    setup:
      (props, { attrs, slots }) =>
      () =>
        props.open
          ? h('section', [
              slots.default?.(),
              attrs.onOk
                ? h('button', { onClick: attrs.onOk }, '确认恢复')
                : null,
            ])
          : null,
  });
  return {
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup: (props) => () => h('p', props.message),
    }),
    Button: slot('button'),
    Drawer: dialog,
    Modal: dialog,
    Spin: slot('div'),
    Tag: slot('span'),
    Input: {
      TextArea: defineComponent({
        props: { value: String },
        emits: ['update:value'],
        setup:
          (props, { emit }) =>
          () =>
            h('textarea', {
              value: props.value,
              onInput: (event: Event) =>
                emit(
                  'update:value',
                  (event.target as HTMLTextAreaElement).value,
                ),
            }),
      }),
    },
    Table: defineComponent({
      props: { dataSource: { type: Array, default: () => [] } },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            props.dataSource.map((record) =>
              slots.bodyCell?.({ record, column: { key: 'actions' } }),
            ),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
let view: { open: (id: string, revision: number) => Promise<void> };
const version = {
  id: '9007199254740993',
  pipelineId: '77',
  revision: 2,
  eventType: 'PUBLISH' as const,
  actorId: '9007199254740995',
  contentHash: 'hash',
  createTime: 1,
};
async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mountVersions() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(VersionHistory, {
    canRestore: true,
    onRestored: harness.restored,
  });
  view = app.mount(host) as unknown as typeof view;
  await view.open('77', 3);
  await flush();
}
function button(text: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.user = reactive({ userInfo: { userId: '7' } });
  vi.mocked(api.getEtlVersionPage).mockResolvedValue({
    list: [version],
    total: 1,
  });
  vi.mocked(api.getEtlVersion).mockResolvedValue({
    ...version,
    configJson: '{"id":9007199254740993123}',
  });
  vi.mocked(api.diffEtlVersion).mockResolvedValue([
    { type: 'NODE_CHANGED', objectId: 'node-1' },
  ]);
  vi.mocked(compareApi.compareEtlVersions).mockResolvedValue({
    sourceVersionId: version.id,
    sourceRevision: 2,
    targetVersionId: '9007199254740997',
    targetRevision: 4,
    changes: [{ type: 'SUBFLOW_VERSION_CHANGED', objectId: 'call-1', count: 1 }],
    changeCounts: { SUBFLOW_VERSION_CHANGED: 1 },
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('etl 版本快照与恢复', () => {
  it('显示快照原文和已保存草稿差异，不损失大整数', async () => {
    await mountVersions();
    button('快照与差异').click();
    await flush();
    expect(api.diffEtlVersion).toHaveBeenCalledWith(version.id);
    expect(host.textContent).toContain('节点配置变化 node-1');
    expect(host.textContent).toContain('9007199254740993123');
  });

  it('恢复冲突不触发画布替换，成功后才以新revision通知设计器', async () => {
    vi.mocked(api.restoreEtlVersion)
      .mockRejectedValueOnce(new Error('revision conflict'))
      .mockResolvedValueOnce(4);
    await mountVersions();
    button('恢复为新草稿').click();
    await flush();
    const input = host.querySelector('textarea')!;
    input.value = '恢复已验证的处理逻辑';
    input.dispatchEvent(new Event('input'));
    await flush();
    button('确认恢复').click();
    await flush();
    expect(harness.restored).not.toHaveBeenCalled();
    expect(host.textContent).toContain('当前画布保持不变');
    expect(api.restoreEtlVersion).toHaveBeenCalledWith({
      versionId: version.id,
      expectedRevision: 3,
      reason: '恢复已验证的处理逻辑',
    });
    button('确认恢复').click();
    await flush();
    expect(harness.restored).toHaveBeenCalledWith(4);
    expect(host.textContent).toBe('');
  });

  it('切换登录会话后不展示旧会话快照', async () => {
    let resolve!: (value: typeof version) => void;
    vi.mocked(api.getEtlVersion).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    await mountVersions();
    button('快照与差异').click();
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    resolve(version);
    await flush();
    expect(host.textContent).toBe('');
  });

  it('比较两个不可变版本时无损传输 ID，并仅展示安全分类', async () => {
    vi.mocked(api.getEtlVersionPage).mockResolvedValueOnce({
      list: [version, { ...version, id: '9007199254740997', revision: 4 }],
      total: 2,
    });
    await mountVersions();
    const selectButtons = [...host.querySelectorAll('button')].filter(
      (item) => item.textContent?.trim() === '选择比较',
    );
    selectButtons[0]!.click();
    selectButtons[1]!.click();
    await flush();
    button('比较两个版本').click();
    await flush();

    expect(compareApi.compareEtlVersions).toHaveBeenCalledWith({
      sourceVersionId: '9007199254740993',
      targetVersionId: '9007199254740997',
    });
    expect(host.textContent).toContain('子流程固定版本变化 call-1');
    expect(host.textContent).not.toContain('nodeJson');
  });

  it('切换登录会话后丢弃迟到的不可变版本比较', async () => {
    vi.mocked(api.getEtlVersionPage).mockResolvedValueOnce({
      list: [version, { ...version, id: '9007199254740997', revision: 4 }],
      total: 2,
    });
    let resolve!: (value: Awaited<ReturnType<typeof compareApi.compareEtlVersions>>) => void;
    vi.mocked(compareApi.compareEtlVersions).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done;
      }),
    );
    await mountVersions();
    const selectButtons = [...host.querySelectorAll('button')].filter(
      (item) => item.textContent?.trim() === '选择比较',
    );
    selectButtons[0]!.click();
    selectButtons[1]!.click();
    button('比较两个版本').click();
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    resolve({
      sourceVersionId: version.id,
      targetVersionId: '9007199254740997',
      changes: [{ type: 'NODE_CONFIG_CHANGED', objectId: 'late-secret', count: 1 }],
      changeCounts: { NODE_CONFIG_CHANGED: 1 },
    });
    await flush();
    expect(host.textContent).toBe('');
  });
});
