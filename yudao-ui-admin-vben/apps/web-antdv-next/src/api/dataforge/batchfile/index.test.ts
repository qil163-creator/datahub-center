import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createBatchFile,
  deleteBatchFile,
  executeBatchFile,
  getBatchFile,
  getBatchFilePage,
  getBatchFileRun,
  importSystemFile,
  resolveBatchFileRun,
  updateBatchFileStatus,
  uploadBatchFile,
} from './index';

const client = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));
vi.mock('#/api/request', () => ({ requestClient: client }));
beforeEach(() => vi.clearAllMocks());
describe('批量文件入库 API 合同', () => {
  it('编号、版本和执行幂等键保持字符串往返', () => {
    const id = '9007199254740993';
    getBatchFile(id);
    getBatchFileRun(id);
    deleteBatchFile(id, '9007199254740995');
    executeBatchFile({ id, requestId: 'request-identity' });
    updateBatchFileStatus({ id, online: true, revision: '9007199254740995' });
    expect(client.get).toHaveBeenCalledWith('/dataforge/batch-file/get', {
      params: { id },
    });
    expect(client.delete).toHaveBeenCalledWith('/dataforge/batch-file/delete', {
      params: { id, revision: '9007199254740995' },
    });
    expect(client.post).toHaveBeenCalledWith('/dataforge/batch-file/execute', {
      id,
      requestId: 'request-identity',
    });
    expect(client.put).toHaveBeenCalledWith('/dataforge/batch-file/status', {
      id,
      online: true,
      revision: '9007199254740995',
    });
  });
  it('文件仅传给领域 multipart 接口，系统路径不拼接到 URL', () => {
    const file = new File(['编码\n1'], '资料.csv');
    uploadBatchFile(file);
    const [url, form, config] = client.post.mock.calls[0]!;
    expect(url).toBe('/dataforge/batch-file/upload');
    expect(form).toBeInstanceOf(FormData);
    expect((form as FormData).get('file')).toEqual(file);
    expect(config).toMatchObject({ timeout: 120_000 });
    importSystemFile({
      connectionId: '9007199254740993',
      path: '/中文 & data.csv',
    });
    expect(client.post).toHaveBeenLastCalledWith(
      '/dataforge/batch-file/system-file',
      { connectionId: '9007199254740993', path: '/中文 & data.csv' },
      { timeout: 120_000 },
    );
  });
  it('分页筛选与保存配置不触发执行接口', () => {
    getBatchFilePage({
      pageNo: 2,
      pageSize: 20,
      taskName: '文件',
      lastStatus: 'UNKNOWN',
    });
    expect(client.get).toHaveBeenCalledWith('/dataforge/batch-file/page', {
      params: {
        pageNo: 2,
        pageSize: 20,
        taskName: '文件',
        lastStatus: 'UNKNOWN',
      },
    });
    createBatchFile({
      taskName: '任务',
      responsibleUserId: '1',
      targetDatabaseId: '2',
      files: [],
      targets: [],
    });
    expect(client.post.mock.calls.map(([url]) => url)).toEqual([
      '/dataforge/batch-file/create',
    ]);
    resolveBatchFileRun({
      id: '3',
      reason: '已核对',
      confirmedStopped: true,
      outputReviewed: true,
    });
    expect(client.post).toHaveBeenLastCalledWith(
      '/dataforge/batch-file/resolve',
      {
        id: '3',
        reason: '已核对',
        confirmedStopped: true,
        outputReviewed: true,
      },
    );
  });
});
