import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  deleteDataFiles,
  downloadDataFile,
  downloadDataFiles,
  getDataFilePage,
  uploadDataFile,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn(), download: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());
describe('文件源网关契约', () => {
  it('上传按multipart发送大编号、逻辑路径和覆盖选项', async () => {
    const file = new File(['content'], 'report.txt');
    await uploadDataFile(
      file,
      { connectionId: '9223372036854775806', path: '/资料' },
      true,
    );
    const [url, form, config] = vi.mocked(requestClient.post).mock.calls[0]!;
    expect(url).toBe('/dataforge/file-resource/upload');
    expect(form).toBeInstanceOf(FormData);
    expect((form as FormData).get('connectionId')).toBe('9223372036854775806');
    expect((form as FormData).get('path')).toBe('/资料');
    expect((form as FormData).get('overwrite')).toBe('true');
    expect((form as FormData).get('file')).toBe(file);
    expect(config).toEqual(
      expect.objectContaining({
        headers: { 'Content-Type': 'multipart/form-data' },
      }),
    );
  });
  it('下载仅请求固定网关端点，不信任外部文件URL', async () => {
    const location = {
      connectionId: '9007199254740993',
      path: '/目录/文件.csv',
    };
    await downloadDataFile(location);
    expect(requestClient.download).toHaveBeenCalledWith(
      '/dataforge/file-resource/download',
      expect.objectContaining({ params: location }),
    );
    const batch = {
      connectionId: location.connectionId,
      paths: [location.path],
    };
    await downloadDataFiles(batch);
    expect(requestClient.download).toHaveBeenCalledWith(
      '/dataforge/file-resource/download-batch',
      expect.objectContaining({ method: 'POST', data: batch }),
    );
  });
  it('列表分页与删除路径不转换或重编码', async () => {
    const params = {
      connectionId: '9007199254740993',
      path: '/资料',
      pageNo: 2,
      pageSize: 10,
      name: '季度',
    };
    await getDataFilePage(params);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/file-resource/page',
      { params },
    );
    const batch = {
      connectionId: params.connectionId,
      paths: ['/资料/季度.csv'],
    };
    await deleteDataFiles(batch);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/file-resource/delete',
      batch,
      expect.anything(),
    );
  });
});
