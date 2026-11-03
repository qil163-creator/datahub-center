/* eslint-disable vue/one-component-per-file -- 内置预览和自定义上传回显共用组件依赖桩 */
import type { SetupContext } from 'vue';

import { createApp, h, nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import ConnectionIconUpload from './connection-icon-upload.vue';

const state = vi.hoisted(() => ({
  upload: {} as Record<string, unknown>,
  uploadAttrs: {} as Record<string, unknown>,
}));
vi.mock('#/components/upload/image-upload.vue', () => ({
  default: {
    props: ['value', 'listType', 'showUploadList', 'maxNumber'],
    emits: ['update:value'],
    setup(props: Record<string, unknown>, { attrs, emit }: SetupContext) {
      state.upload = props;
      state.uploadAttrs = attrs;
      return () =>
        h(
          'button',
          {
            onClick: () =>
              emit('update:value', 'https://files.test/upload.svg'),
          },
          '上传',
        );
    },
  },
}));

let app: ReturnType<typeof createApp> | undefined;
afterEach(() => {
  app?.unmount();
  vi.unstubAllEnvs();
});

describe('连接图标上传预览', () => {
  it('复用上传能力并以共享图标展示内置路径，不把部署前缀写回表单', async () => {
    vi.stubEnv('BASE_URL', '/console/');
    const update = vi.fn();
    const target = document.createElement('div');
    app = createApp({
      render: () =>
        h(ConnectionIconUpload, {
          value: '/connection-icons/db2.png',
          'onUpdate:value': update,
        }),
    });
    app.mount(target);
    expect(state.upload.value).toBe('/connection-icons/db2.png');
    expect(state.upload.listType).toBe('text');
    expect(state.upload.showUploadList).toEqual({ showPreviewIcon: false });
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/console/connection-icons/db2.png',
    );
    target.querySelector('img')!.dispatchEvent(new Event('error'));
    await nextTick();
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/console/connection-icons/database.png',
    );
    target.querySelector('button')!.click();
    expect(update).toHaveBeenCalledExactlyOnceWith(
      'https://files.test/upload.svg',
    );
  });

  it('已知类型预览本地图标，上传自定义图标后立即回显且不透传类型给上传器', async () => {
    vi.stubEnv('BASE_URL', '/console/');
    const value = ref('/connection-icons/database.png');
    const change = vi.fn();
    const target = document.createElement('div');
    app = createApp({
      render: () =>
        h(ConnectionIconUpload, {
          typeCode: 'MYSQL',
          value: value.value,
          'onUpdate:value': (path: string) => {
            value.value = path;
          },
          onChange: change,
        }),
    });
    app.mount(target);
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      '/console/connection-icons/mysql.svg',
    );
    expect(state.uploadAttrs).not.toHaveProperty('typeCode');
    expect(state.uploadAttrs).not.toHaveProperty('type-code');

    target.querySelector('button')!.click();
    await nextTick();
    expect(value.value).toBe('https://files.test/upload.svg');
    expect(state.upload.value).toBe('https://files.test/upload.svg');
    expect(change).toHaveBeenCalledExactlyOnceWith(
      'https://files.test/upload.svg',
    );
    expect(target.querySelector('img')?.getAttribute('src')).toBe(
      'https://files.test/upload.svg',
    );
  });
});
