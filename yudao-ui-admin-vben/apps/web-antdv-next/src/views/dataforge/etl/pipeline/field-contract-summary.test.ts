import type { App } from 'vue';

import { createApp, h } from 'vue';

import { afterEach, describe, expect, it } from 'vitest';

import FieldContractSummary from './field-contract-summary.vue';

let app: App | undefined;
let host: HTMLDivElement;
afterEach(() => {
  app?.unmount();
  host?.remove();
});
function render(field: {
  dataType?: string;
  nullable?: boolean | null;
  precision?: null | number;
  scale?: null | number;
  timezone?: null | string;
  type?: string;
}) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({ render: () => h(FieldContractSummary, { field }) });
  app.mount(host);
  return host.textContent?.replaceAll(/\s+/g, ' ').trim();
}
describe('字段契约事实展示', () => {
  it('旧协议缺少属性时明确未知，不从类型文本推断精度、空值或时区', () => {
    expect(render({ dataType: 'DECIMAL(38,9)' })).toBe(
      '类型 DECIMAL(38,9) · 可空性 未知 · 精度 未知 · scale 未知 · 时区 未知',
    );
  });
  it('显式false和负scale保留，null不是0或不可空', () => {
    expect(
      render({
        type: 'Decimal',
        nullable: false,
        precision: null,
        scale: -2,
        timezone: null,
      }),
    ).toBe('类型 Decimal · 可空性 禁止空值 · 精度 未知 · scale -2 · 时区 未知');
  });
  it('展示已知true、零scale和明确时区事实，不改写元数据', () => {
    const field = {
      dataType: 'NUMERIC',
      nullable: true,
      precision: 38,
      scale: 0,
      timezone: 'UTC',
    };
    const original = { ...field };
    expect(render(field)).toBe(
      '类型 NUMERIC · 可空性 允许空值 · 精度 38 · scale 0 · 时区 UTC',
    );
    expect(field).toEqual(original);
  });
});
