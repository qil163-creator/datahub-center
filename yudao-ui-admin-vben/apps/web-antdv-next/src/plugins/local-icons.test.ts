import { createApp, h, nextTick } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { describe, expect, it } from 'vitest';

import './local-icons';

describe('本地操作图标', () => {
  it.each(['lucide:plus', 'lucide:ellipsis-vertical', 'lucide:refresh-cw', 'lucide:undo-2', 'lucide:search', 'ep:plus', 'carbon:play', 'ant-design:upload-outlined', 'icon-park-outline:plus', 'mdi:content-save'])('无需外部图标服务即可渲染 %s', async (icon) => {
    const host = document.createElement('div');
    const app = createApp({ render: () => h(IconifyIcon, { icon }) });
    app.mount(host);
    await nextTick();
    expect(host.querySelector('svg path, svg circle, svg rect, svg line')).not.toBeNull();
    app.unmount();
  });
});
