import type { App } from 'vue';

import type { VbenFormSchema } from '#/adapter/form';

import { createApp, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import RegistrationForm from './form.vue';

interface ModalOptions {
  onConfirm: () => Promise<void>;
  onOpenChange: (open: boolean) => Promise<void>;
}
const harness = vi.hoisted(() => ({
  access: {
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: ['governance:doc:create'],
  },
  options: undefined as ModalOptions | undefined,
  schema: [] as VbenFormSchema[],
  context: {} as Record<string, unknown>,
  values: {} as Record<string, unknown>,
  create: vi.fn(),
  update: vi.fn(),
  get: vi.fn(),
  upload: vi.fn(),
  lock: vi.fn(),
  unlock: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/hooks', () => ({ getDictOptions: () => [] }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: ModalOptions) => {
    harness.options = options;
    return [
      () => null,
      {
        getData: () => harness.context,
        setState: vi.fn(),
        lock: harness.lock,
        unlock: harness.unlock,
        close: () => options.onOpenChange(false),
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: () => null,
  Button: () => null,
  message: { success: vi.fn(), warning: harness.warning },
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: (options: { schema: VbenFormSchema[] }) => {
    harness.schema = options.schema;
    return [
      () => null,
      {
        resetForm: async () => {
          harness.values = {};
        },
        setValues: async (values: Record<string, unknown>) => {
          Object.assign(harness.values, values);
        },
        getValues: async () => ({ ...harness.values }),
        validate: async () => ({ valid: true }),
        setDisabled: vi.fn(),
      },
    ];
  },
}));
vi.mock('#/api/governance/doc', () => ({
  getDoc: (...args: unknown[]) => harness.get(...args),
  getDocCategories: async () => [
    { id: '9007199254740993', categoryName: '标准类目' },
  ],
  getDocSimpleList: async () => [],
  createDocRegistration: (...args: unknown[]) => harness.create(...args),
  updateDocRegistration: (...args: unknown[]) => harness.update(...args),
}));
vi.mock('#/api/infra/file', () => ({
  uploadFileWithInfo: (...args: unknown[]) => harness.upload(...args),
}));

let app: App;
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function upload() {
  const field = harness.schema.find((item) => item.fieldName === 'fileUrl')!;
  return (
    field.componentProps as { api: (file: File) => Promise<unknown> }
  ).api(new File(['pdf'], 'standard.pdf'));
}
async function open(context: Record<string, unknown> = {}) {
  harness.context = context;
  await harness.options!.onOpenChange(true);
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessToken: 'session-a',
    refreshToken: '',
    accessCodes: ['governance:doc:create'],
  });
  harness.context = {};
  harness.values = {};
  harness.create.mockResolvedValue('9007199254740994');
  app = createApp(RegistrationForm);
  app.mount(document.createElement('div'));
});
afterEach(() => app.unmount());

describe('标准登记上传与保存', () => {
  it('上传完成前不提交，元数据与URL完成后无损提交编号和LocalDate', async () => {
    const pending = deferred<{ id: string; name: string; url: string }>();
    harness.upload.mockReturnValue(pending.promise);
    await open({ categoryId: '9007199254740993', stdType: 'NATIONAL' });
    Object.assign(harness.values, {
      stdCode: 'GB-1',
      nameCn: '标准',
      publishDate: '2026-10-19',
      implementDate: '2026-10-20',
      abolishDate: '2027-10-19',
    });
    const uploading = upload();
    expect(harness.upload).toHaveBeenCalledWith(
      { file: expect.any(File), directory: 'standard/doc' },
      undefined,
    );
    await harness.options!.onConfirm();
    expect(harness.create).not.toHaveBeenCalled();
    pending.resolve({
      id: '9007199254740995',
      name: 'standard.pdf',
      url: 'https://example.test/file.pdf',
    });
    await uploading;
    await harness.options!.onConfirm();
    expect(harness.create).toHaveBeenCalledWith(
      expect.objectContaining({
        categoryId: '9007199254740993',
        fileId: '9007199254740995',
        fileUrl: 'https://example.test/file.pdf',
        publishDate: '2026-10-19',
        implementDate: '2026-10-20',
        abolishDate: '2027-10-19',
        implementStatus: 'DRAFT',
      }),
    );
  });
  it('只有URL而没有平台文件编号不能视为上传成功', async () => {
    await open({ categoryId: '9007199254740993' });
    harness.upload.mockResolvedValue({ url: 'https://example.test/file.pdf' });
    await expect(upload()).rejects.toThrow('文件登记尚未完成');
    expect(harness.values.fileId).toBeNull();
    expect(harness.values.fileUrl).toBeNull();
    expect(harness.unlock).toHaveBeenCalled();
  });
  it('拒绝旧上传接口的纯URL响应', async () => {
    await open();
    harness.upload.mockResolvedValue('https://example.test/file.pdf');
    await expect(upload()).rejects.toThrow('文件登记尚未完成');
    expect(harness.values.fileId).toBeNull();
    expect(harness.values.fileUrl).toBeNull();
  });
  it('关闭后迟到的上传不能覆盖下一次表单', async () => {
    const pending = deferred<{ id: string; url: string }>();
    harness.upload.mockReturnValue(pending.promise);
    await open();
    const uploading = upload();
    await harness.options!.onOpenChange(false);
    await open({ categoryId: '9007199254740993' });
    pending.resolve({
      id: '9007199254740995',
      url: 'https://example.test/file.pdf',
    });
    await expect(uploading).rejects.toThrow('当前标准表单已关闭');
    expect(harness.values.fileId).toBeNull();
    expect(harness.values.categoryId).toBe('9007199254740993');
  });
  it('旧记录从登记页保存也必须补全必填字段', async () => {
    harness.get.mockResolvedValue({
      id: '9007199254740993',
      nameCn: '历史标准',
      stdCode: '',
      categoryId: null,
      fileId: null,
      fileUrl: null,
    });
    await open({ id: '9007199254740993' });
    await harness.options!.onConfirm();
    expect(harness.update).not.toHaveBeenCalled();
    expect(harness.warning).toHaveBeenCalled();
  });
});
