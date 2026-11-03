import type { App } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import Editor from './table-input-read-options.vue';

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => { app?.unmount(); host?.remove(); vi.restoreAllMocks(); });
async function flush() { for (let index = 0; index < 5; index++) await nextTick(); }

describe('表输入真实日期与表格控件', () => {
  it('数值与时间分批切换隔离日期值，条件表格与弹窗正常渲染', async () => {
    const error = vi.spyOn(console, 'error');
    const state = reactive({
      config: {
        readMode: 'FULL',
        batchRead: { enabled: true, mode: 'ID', field: 'id', start: '9007199254740993', end: '9007199254740999', size: '100', timeUnit: 'HOUR', maxRetries: 3, retryIntervalSeconds: 60 },
        timeConditions: [{ field: 'created_at', baselineType: 'FIXED', baselineValue: '2026-11-03', operator: '>=' }],
      },
      fields: [{ fieldName: 'id', fieldTypeName: 'BIGINT' }, { fieldName: 'created_at', fieldTypeName: 'TIMESTAMP' }],
    });
    host = document.createElement('div'); document.body.append(host);
    app = createApp({ render: () => h(Editor, state) }); app.mount(host); await flush();
    function chooseBatchMode(label: string) {
      const radio = [...host.querySelectorAll<HTMLLabelElement>('.ant-radio-wrapper')].find((item) => item.textContent?.includes(label))!;
      expect(radio).toBeTruthy(); radio.querySelector<HTMLInputElement>('input')!.click();
    }
    expect(host.querySelector<HTMLInputElement>('[aria-label="起始ID值"]')!.value).toBe('9007199254740993');
    chooseBatchMode('按时间分批'); await flush();
    expect(state.config.batchRead.mode).toBe('TIME');
    expect(host.querySelector<HTMLInputElement>('input[placeholder="请选择开始时间"]')!.value).toBe('');
    chooseBatchMode('按ID分批'); await flush();
    expect(host.querySelector<HTMLInputElement>('[aria-label="起始ID值"]')!.value).toBe('9007199254740993');
    state.config.readMode = 'TIME_INCREMENT'; await flush();
    expect(host.querySelector('tbody')?.textContent).toContain('created_at');
    const edit = [...host.querySelectorAll('button')].find((button) => button.textContent?.includes('编辑'))!;
    edit.click(); await flush();
    expect(document.querySelector('.qi-table-input-condition-modal')).toBeTruthy();
    expect(document.querySelector<HTMLInputElement>('input[placeholder="请选择固定时间"]')!.value).toBe('2026-11-03');
    expect(error.mock.calls.flat().join(' ')).not.toMatch(/Invalid Date|isValid is not a function|Unhandled error/);
  });
});
