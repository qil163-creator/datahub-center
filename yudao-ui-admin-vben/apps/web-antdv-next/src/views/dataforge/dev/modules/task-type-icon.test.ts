import type { DevTaskIconInput } from '../task-icon-model';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import TaskTypeIcon from './task-type-icon.vue';

vi.mock('@vben/icons', () => ({
  IconifyIcon: {
    props: ['icon'],
    setup(props: { icon: { body: string } }) {
      return () => h('svg', { 'data-local-icon': props.icon.body });
    },
  },
}));

let app: ReturnType<typeof createApp> | undefined;
let target: HTMLDivElement | undefined;

function mountTaskIcon(props: DevTaskIconInput) {
  target = document.createElement('div');
  app = createApp({ render: () => h(TaskTypeIcon, props) });
  app.mount(target);
  return target;
}

afterEach(() => {
  app?.unmount();
  target?.remove();
});

describe('任务图标渲染与加载失败', () => {
  it('产品图片失败后使用对应产品的离线图标，切换路径后重试', async () => {
    const props = reactive({
      databaseIconPath: '/database/mysql.svg',
      dbType: 'MYSQL',
      taskType: 'SQL',
    });
    const element = mountTaskIcon(props);

    expect(element.querySelector('img')?.getAttribute('src')).toBe(
      '/database/mysql.svg',
    );
    element.querySelector('img')?.dispatchEvent(new Event('error'));
    await nextTick();
    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('svg[data-local-icon]')).not.toBeNull();
    expect(
      element.querySelector('[role="img"]')?.getAttribute('aria-label'),
    ).toBe('MySQL');

    props.dbType = 'HIVE';
    props.databaseIconPath = '/database/hive.svg';
    await nextTick();
    expect(element.querySelector('img')?.getAttribute('src')).toBe(
      '/database/hive.svg',
    );
  });

  it('集群任务保留引擎图标，不加载底层数据库图片', () => {
    const element = mountTaskIcon({
      databaseIconPath: '/database/mysql.svg',
      dbType: 'MYSQL',
      taskType: 'SPARK_SQL',
    });
    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('svg[data-local-icon]')).not.toBeNull();
    expect(
      element.querySelector('[role="img"]')?.getAttribute('aria-label'),
    ).toBe('Apache Spark');
  });
});
