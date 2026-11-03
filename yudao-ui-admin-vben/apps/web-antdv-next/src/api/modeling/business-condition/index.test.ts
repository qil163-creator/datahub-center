import { describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import { getBusinessConditionSourceFields } from './index';

vi.mock('#/api/request', () => ({ requestClient: { get: vi.fn() } }));

describe('业务限定当前来源字段契约', () => {
  it('按无损模型编号读取当前设计字段', async () => {
    const modelId = '9223372036854775806';
    await getBusinessConditionSourceFields(modelId);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/modeling/business-condition/source-fields',
      { params: { modelId } },
    );
  });
});
