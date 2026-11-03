import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { uploadFile, uploadFileWithInfo } from './index';

vi.mock('#/api/request', () => ({ requestClient: { upload: vi.fn() } }));
beforeEach(() => vi.resetAllMocks());

describe('文件上传兼容契约', () => {
  it('完整上传通过平台端点提交文件，返回已登记的无损编号', async () => {
    const file = new File(['standard'], 'standard.pdf', {
      type: 'application/pdf',
    });
    const onUploadProgress = vi.fn();
    const response = {
      id: '9223372036854775807',
      name: 'standard.pdf',
      url: 'https://example.test/standard.pdf',
    };
    vi.mocked(requestClient.upload).mockResolvedValue(response);
    expect(
      await uploadFileWithInfo(
        { file, directory: 'standard/doc' },
        onUploadProgress,
      ),
    ).toEqual(response);
    expect(requestClient.upload).toHaveBeenCalledExactlyOnceWith(
      '/infra/file/upload-with-info',
      { file, directory: 'standard/doc' },
      { onUploadProgress },
    );
  });
  it('原URL上传入口继续使用旧路径与返回值', async () => {
    const file = new File(['standard'], 'standard.pdf');
    vi.mocked(requestClient.upload).mockResolvedValue(
      'https://example.test/standard.pdf',
    );
    expect(await uploadFile({ file, directory: 'standard/doc' })).toBe(
      'https://example.test/standard.pdf',
    );
    expect(requestClient.upload).toHaveBeenCalledExactlyOnceWith(
      '/infra/file/upload',
      { file, directory: 'standard/doc' },
      { onUploadProgress: undefined },
    );
  });
  it('元数据登记失败按失败传播，不能发布完成结果', async () => {
    vi.mocked(requestClient.upload).mockRejectedValue(
      new Error('文件元数据登记失败'),
    );
    await expect(
      uploadFileWithInfo({ file: new File(['standard'], 'standard.pdf') }),
    ).rejects.toThrow('文件元数据登记失败');
  });
});
