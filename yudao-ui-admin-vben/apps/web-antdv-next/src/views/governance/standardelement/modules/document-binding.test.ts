import { describe, expect, it, vi } from 'vitest';

import { createDocumentBindingValidator } from './document-binding';

describe('标准登记类型联动', () => {
  it('按准确Long ID核对，分页之外的合法登记不会被清空', async () => {
    const load = vi.fn(async () => ({ stdType: 'NATIONAL' }));
    const clear = vi.fn();
    await createDocumentBindingValidator(load)(
      { documentId: '9007199254740993', stdType: 'NATIONAL' },
      clear,
    );
    expect(load).toHaveBeenCalledExactlyOnceWith('9007199254740993');
    expect(clear).not.toHaveBeenCalled();
  });
  it('只在已读取的登记类型不符时清空，清空筛选和读取失败均保留关联', async () => {
    const load = vi.fn(async () => ({ stdType: 'NATIONAL' }));
    const clear = vi.fn();
    const validate = createDocumentBindingValidator(load);
    await validate({ documentId: 1, stdType: 'LOCAL' }, clear);
    expect(clear).toHaveBeenCalledTimes(1);
    await validate({ documentId: 1 }, clear);
    load.mockRejectedValueOnce(new Error('读取失败'));
    await validate({ documentId: 1, stdType: 'LOCAL' }, clear);
    expect(clear).toHaveBeenCalledTimes(1);
  });
  it('登记或筛选改变后，旧请求不能解除新关联', async () => {
    let resolve!: (value: { stdType: string }) => void;
    const pending = new Promise<{ stdType: string }>((yes) => {
      resolve = yes;
    });
    const load = vi
      .fn()
      .mockReturnValueOnce(pending)
      .mockResolvedValueOnce({ stdType: 'LOCAL' });
    const clear = vi.fn();
    const validate = createDocumentBindingValidator(load);
    const old = validate(
      { documentId: '9007199254740993', stdType: 'LOCAL' },
      clear,
    );
    await validate({ documentId: '9007199254740994', stdType: 'LOCAL' }, clear);
    resolve({ stdType: 'NATIONAL' });
    await old;
    expect(clear).not.toHaveBeenCalled();
  });
});
