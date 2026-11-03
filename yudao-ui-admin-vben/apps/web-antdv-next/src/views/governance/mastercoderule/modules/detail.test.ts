import type { App } from 'vue';

import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Detail from './detail.vue';

interface DrawerOptions {
  onOpenChange: (open: boolean) => Promise<void>;
  placement: string;
  showConfirmButton: boolean;
}
const harness = vi.hoisted(() => ({
  access: {} as { accessToken: string; refreshToken?: string },
  data: {} as { id?: string },
  get: vi.fn(),
  options: undefined as DrawerOptions | undefined,
  state: {} as { loading?: boolean },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenDrawer: (options: DrawerOptions) => {
    harness.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('aside', slots.default?.()),
      }),
      {
        getData: () => harness.data,
        close: () => options.onOpenChange(false),
        setState: (value: { loading?: boolean }) => {
          Object.assign(harness.state, value);
        },
      },
    ];
  },
}));
vi.mock('#/api/governance/mastermanage', () => ({
  getMasterCodeRule: harness.get,
}));
vi.mock('#/views/modeling/modelcommon/detail-field-form.vue', () => ({
  default: defineComponent({
    props: { fields: Array },
    setup: (props) => () => h('section', JSON.stringify(props.fields)),
  }),
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({
    props: { message: String },
    setup: (props) => () => h('p', props.message),
  }),
}));
let app: App | undefined;
let host: HTMLDivElement;
const rule: GovernanceMasterManageApi.CodeRule = {
  id: '9007199254740995',
  modelId: '9007199254740997',
  modelName: '人员模型',
  code: 'PERSON',
  name: '人员编码规则',
  fieldName: 'person_code',
  description: '人员编码说明',
  status: 0,
  example: 'P-0001',
  segments: [
    { segmentType: 'CONSTANT', segmentValue: 'P', sepChar: '-' },
    { segmentType: 'SEQUENCE', segmentValue: '4' },
  ],
};
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1' });
  harness.data = { id: rule.id as string };
  harness.state = {};
  harness.get.mockResolvedValue(rule);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Detail);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('编码规则只读详情', () => {
  it('按无损编号读取详情并展示规则、模型、字段、示例与编码段', async () => {
    await harness.options!.onOpenChange(true);
    await nextTick();
    expect(harness.get).toHaveBeenCalledWith('9007199254740995');
    expect(harness.options).toMatchObject({
      placement: 'right',
      showConfirmButton: false,
    });
    for (const text of [
      '人员编码规则',
      '人员模型',
      'person_code',
      'P-0001',
      '人员编码说明',
      '固定值',
      '流水号',
    ]) {
      expect(host.textContent).toContain(text);
    }
    expect(host.querySelector('button')).toBeNull();
    expect(harness.state.loading).toBe(false);
  });
  it.each(['close', 'session', 'unmount'] as const)(
    '%s 后忽略未完成的详情响应',
    async (change) => {
      let finish!: (value: GovernanceMasterManageApi.CodeRule) => void;
      harness.get.mockReturnValueOnce(
        new Promise<GovernanceMasterManageApi.CodeRule>((resolve) => {
          finish = resolve;
        }),
      );
      const pending = harness.options!.onOpenChange(true);
      if (change === 'close') await harness.options!.onOpenChange(false);
      if (change === 'session') {
        harness.access.refreshToken = '2';
        await nextTick();
      }
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      finish(rule);
      await pending;
      await nextTick();
      expect(host.textContent).not.toContain('人员编码规则');
    },
  );
});
