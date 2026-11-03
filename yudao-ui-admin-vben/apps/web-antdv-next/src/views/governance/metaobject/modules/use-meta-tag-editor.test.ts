import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  bindMetaTags,
  getMetaTagBindingSnapshot,
  getMetaTagSimpleList,
} from '#/api/governance/metatag';

import { useMetaTagEditor } from './use-meta-tag-editor';

const harness = vi.hoisted(() => ({
  access: undefined as
    | undefined
    | { accessToken: string; refreshToken: string },
}));
vi.mock('@vben/stores', () => {
  harness.access = reactive({ accessToken: 'session-1', refreshToken: '' });
  return { useAccessStore: () => harness.access };
});
vi.mock('#/api/governance/metatag', () => ({
  bindMetaTags: vi.fn(),
  getMetaTagBindingSnapshot: vi.fn(),
  getMetaTagSimpleList: vi.fn(),
}));

let app: App | undefined;
let host: HTMLDivElement;
let editor: ReturnType<typeof useMetaTagEditor>;
const id = ref<number | string | undefined>('9007199254740993');
const tagId = '9007199254740995';
const binding = (objectId = id.value!, name = '敏感标签') => ({
  objectType: 2,
  objectId,
  revision: 7,
  tagIds: [tagId],
  tags: [{ id: tagId, name }],
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}

beforeEach(() => {
  vi.resetAllMocks();
  id.value = '9007199254740993';
  Object.assign(harness.access!, {
    accessToken: 'session-1',
    refreshToken: '',
  });
  vi.mocked(getMetaTagSimpleList).mockResolvedValue([
    { id: tagId, name: '敏感标签' },
  ]);
  vi.mocked(getMetaTagBindingSnapshot).mockImplementation(async () =>
    binding(),
  );
  vi.mocked(bindMetaTags).mockResolvedValue(true);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(
    defineComponent({
      setup() {
        editor = useMetaTagEditor(() => id.value);
        return () => h('div');
      },
    }),
  );
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('元数据标签全量覆盖保护', () => {
  it.each(['binding', 'options'] as const)(
    '首次%s读取失败不能以空集保存',
    async (source) => {
      if (source === 'binding')
        vi.mocked(getMetaTagBindingSnapshot).mockRejectedValueOnce(
          new Error('读失败'),
        );
      else
        vi.mocked(getMetaTagSimpleList).mockRejectedValueOnce(
          new Error('读失败'),
        );
      await editor.load();
      expect(editor.ready.value).toBe(false);
      expect(editor.error.value).toContain('读取失败');
      expect(await editor.save()).toBe(false);
      expect(bindMetaTags).not.toHaveBeenCalled();
    },
  );

  it('刷新失败保留已读绑定和选择，成功重试后才允许保存', async () => {
    await editor.load();
    vi.mocked(getMetaTagBindingSnapshot).mockRejectedValueOnce(
      new Error('读失败'),
    );
    await editor.load();
    expect(editor.boundTags.value).toEqual(binding().tags);
    expect(editor.selectedTagIds.value).toEqual([tagId]);
    expect(editor.ready.value).toBe(false);
    await editor.load();
    expect(editor.ready.value).toBe(true);
  });

  it('只有明确清空才提交空数组，并保留大ID和修订号', async () => {
    await editor.load();
    expect(editor.dirty.value).toBe(false);
    await editor.save();
    expect(bindMetaTags).not.toHaveBeenCalled();
    editor.selectedTagIds.value = [];
    expect(editor.clearsAll.value).toBe(true);
    vi.mocked(getMetaTagBindingSnapshot).mockResolvedValueOnce({
      ...binding(),
      revision: 8,
      tags: [],
      tagIds: [],
    });
    expect(await editor.save()).toBe(true);
    expect(bindMetaTags).toHaveBeenCalledExactlyOnceWith({
      objectType: 2,
      objectId: '9007199254740993',
      revision: 7,
      tagIds: [],
    });
    expect(editor.boundTags.value).toEqual([]);
  });

  it('版本冲突后不能盲目重试覆盖', async () => {
    await editor.load();
    editor.selectedTagIds.value = [];
    vi.mocked(bindMetaTags).mockRejectedValueOnce(new Error('CAS冲突'));
    expect(await editor.save()).toBe(false);
    expect(editor.ready.value).toBe(false);
    expect(editor.saving.value).toBe(false);
    await editor.save();
    expect(bindMetaTags).toHaveBeenCalledTimes(1);
    expect(editor.error.value).toContain('重新加载');
  });

  it('较旧响应不能覆盖最新对象，也不能提前结束新请求loading', async () => {
    const old = deferred<ReturnType<typeof binding>>();
    const latest = deferred<ReturnType<typeof binding>>();
    vi.mocked(getMetaTagBindingSnapshot)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    const first = editor.load();
    id.value = '9007199254740994';
    const second = editor.load();
    old.resolve(binding('9007199254740993', '旧对象'));
    await first;
    expect(editor.loading.value).toBe(true);
    expect(editor.boundTags.value).toEqual([]);
    latest.resolve(binding('9007199254740994', '新对象'));
    await second;
    expect(editor.loading.value).toBe(false);
    expect(editor.boundTags.value[0]?.name).toBe('新对象');
  });

  it.each(['close', 'session', 'unmount'] as const)(
    '%s后丢弃迟到响应',
    async (action) => {
      const pending = deferred<ReturnType<typeof binding>>();
      vi.mocked(getMetaTagBindingSnapshot).mockReturnValueOnce(pending.promise);
      const loading = editor.load();
      if (action === 'close') id.value = undefined;
      if (action === 'session') harness.access!.refreshToken = 'session-2';
      if (action === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      await nextTick();
      pending.resolve(binding('9007199254740993', '失效对象'));
      await loading;
      expect(editor.boundTags.value).toEqual([]);
      expect(editor.ready.value).toBe(false);
    },
  );
});
