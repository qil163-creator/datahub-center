import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  releaseEtlPipeline,
  resolveEtlCommand,
  resolveEtlSchema,
  runEtlCommand,
  stopEtlCommand,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));
beforeEach(() => vi.clearAllMocks());

describe('etl publication and durable command contract', () => {
  it('publishes the intended revision and preserves long IDs', () => {
    releaseEtlPipeline('9007199254740993', 8);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/release?id=9007199254740993&revision=8',
    );
  });
  it('resolves an unsaved graph without injecting runtime fields', () => {
    const data = { nodes: [], edges: [] };
    resolveEtlSchema(data);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/resolve-schema',
      data,
    );
  });
  it('maps the shared command taskId to the real ETL pipelineId contract', async () => {
    const command = { taskId: '9007199254740993', requestId: 'same-retry' };
    const receipt = {
      recordId: '9007199254740999',
      runStatus: 1,
      replayed: false,
      stopRequested: false,
    };
    vi.mocked(requestClient.post).mockResolvedValue(receipt);
    expect(await runEtlCommand(command)).toEqual(receipt);
    await runEtlCommand(command);
    expect(requestClient.post).toHaveBeenNthCalledWith(
      1,
      '/dataforge/etl-pipeline/run-command',
      {
        pipelineId: command.taskId,
        requestId: command.requestId,
      },
    );
    expect(requestClient.post).toHaveBeenNthCalledWith(
      2,
      '/dataforge/etl-pipeline/run-command',
      {
        pipelineId: command.taskId,
        requestId: command.requestId,
      },
    );
  });
  it('stops the precise record with stable request identity', () => {
    const command = { recordId: '9007199254740993', requestId: 'stop-retry' };
    stopEtlCommand(command);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/stop-command',
      command,
    );
  });
  it('submits the version and separate exit/output evidence for resolution', () => {
    const command = {
      recordId: '9007199254740993',
      requestId: 'resolve-retry',
      expectedVersion: '8',
      confirmedStopped: true as const,
      outputReviewed: true as const,
      evidenceRef: 'incident:quality',
      reason: '已核查原实例和输出',
      outcome: 'FAILED' as const,
    };
    resolveEtlCommand(command);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/dataforge/etl-pipeline/resolve-command',
      command,
    );
  });
});
