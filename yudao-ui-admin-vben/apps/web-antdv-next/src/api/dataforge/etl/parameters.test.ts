import { describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { getEtlRunParameters, runEtlCommand } from './index';
import { getSubflowPipelinePage, getSubflowVersionPage } from './subflow';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn() },
}));
describe('etl参数和子流程目录API', () => {
  it('保留定义修订、字符串数值与无损ID', async () => {
    await getEtlRunParameters('9007199254740993');
    const parameters = {
      id: '9007199254740995',
      amount: '0.1234567890123456789',
    };
    await runEtlCommand({
      taskId: '9007199254740993',
      expectedDefinitionRevision: 8,
      requestId: 'same-request',
      parameters,
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/run-parameters',
      { params: { pipelineId: '9007199254740993' } },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/run-command',
      {
        pipelineId: '9007199254740993',
        expectedDefinitionRevision: 8,
        requestId: 'same-request',
        parameters,
      },
    );
    await getSubflowPipelinePage({ name: '转换', pageNo: 2, pageSize: 20 });
    await getSubflowVersionPage({
      pipelineId: '9007199254740993',
      pageNo: 3,
      pageSize: 20,
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/subflow/pipeline-page',
      { params: { name: '转换', pageNo: 2, pageSize: 20 } },
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/subflow/version-page',
      { params: { pipelineId: '9007199254740993', pageNo: 3, pageSize: 20 } },
    );
  });
});
