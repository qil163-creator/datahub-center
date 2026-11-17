import { createApp } from 'vue';

import { expect, it, vi } from 'vitest';

import Form from './modules/form.vue';

const calls = vi.hoisted(() => ({
  create: vi.fn(),
  getValues: vi.fn(),
  confirm: undefined as (() => Promise<void>) | undefined,
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal(options: { onConfirm: () => Promise<void> }) {
    calls.confirm = options.onConfirm;
    return [() => null, { close: vi.fn(), lock: vi.fn(), unlock: vi.fn() }];
  },
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => null,
    { getValues: calls.getValues, validate: () => ({ valid: true }) },
  ],
}));
vi.mock('#/api/governance/whitelist', () => ({
  createWhitelist: calls.create,
}));
vi.mock('antdv-next', () => ({ message: { success: vi.fn() } }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('./data', () => ({ useFormSchema: () => [] }));
vi.mock('#/views/governance/common/form-layout', () => ({
  GOVERNANCE_MODAL_FORM: {},
}));

it.each([
  ['catalog-9007199254740993', 'CATALOG', '9007199254740993'],
  ['9007199254740995', 'CATEGORY', '9007199254740995'],
  [42, 'CATEGORY', 42],
])('保留所选白名单对象编号 %s', async (value, objectType, objectId) => {
  calls.getValues.mockResolvedValue({
    objectId: value,
    subjectType: 'USER',
    userIds: ['9007199254740997'],
  });
  const app = createApp(Form);
  app.mount(document.createElement('div'));
  try {
    await calls.confirm!();
    expect(calls.create).toHaveBeenLastCalledWith(
      expect.objectContaining({
        objectId,
        objectType,
        whitelistSubjects: [{ subjectId: '9007199254740997' }],
      }),
    );
  } finally {
    app.unmount();
  }
});
