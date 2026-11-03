import { describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { getIndicatorSourceFields } from './index';

vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn() } }));

describe('指标当前来源字段契约', () => {
  it('按无损模型编号读取当前设计字段', async () => {
    const modelId = '9223372036854775806';
    await getIndicatorSourceFields(modelId);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/modeling/indicator-model/source-fields',
      { params: { modelId } },
    );
  });
});
