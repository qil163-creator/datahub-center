import { describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { downloadEtlFileOutput, retireEtlFileOutput } from './file-output';

vi.mock('#/api/request', () => ({
  requestClient: { download: vi.fn(), post: vi.fn() },
}));

describe('etl 文件输出下载 API', () => {
  it('使用无损台账 ID 调用受权下载端点并返回 Blob', async () => {
    const blob = new Blob(['content']);
    vi.mocked(requestClient.download).mockResolvedValueOnce(blob);

    await expect(downloadEtlFileOutput('9007199254740993')).resolves.toBe(blob);
    expect(requestClient.download).toHaveBeenCalledWith(
      '/dataforge/etl-file-output/download',
      { params: { id: '9007199254740993' }, silentError: true },
    );
  });
  it('清理申请无损传输 ID、修订号及明确确认，直接返回后台回执', async () => {
    const data = {
      id: '9007199254740993',
      expectedRevision: '9007199254740997',
      reason: '已归档',
      retireAfter: 1_800_000_000_000,
      confirmDeletion: true as const,
    };
    const receipt = { id: data.id, retentionStatus: 'REQUESTED' };
    vi.mocked(requestClient.post).mockResolvedValueOnce(receipt);
    await expect(retireEtlFileOutput(data)).resolves.toBe(receipt);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/etl-file-output/retire',
      data,
    );
  });
});
