import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createDefaultConfig,
  createWizard,
  deleteWizard,
  getDatabaseOptions,
  getDatabaseTables,
  saveWizardStep,
} from './index';

const request = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));
vi.mock('#/api/request', () => ({ requestClient: request }));

describe('整库同步配置契约', () => {
  beforeEach(() => vi.clearAllMocks());

  it('创建和最终保存仅操作草稿，保留无损编号与版本', async () => {
    const id = '90071992547409931';
    const revision = '90071992547409932';
    const config = {
      ...createDefaultConfig(),
      taskName: '整库配置',
      sourceDatabaseId: id,
      targetDatabaseId: '90071992547409933',
    };
    await createWizard(config);
    await saveWizardStep(id, 4, config, revision);
    expect(request.post).toHaveBeenCalledWith('/dataforge/full-sync/wizard', {
      step: 1,
      config,
    });
    expect(request.put).toHaveBeenCalledWith(
      `/dataforge/full-sync/wizard/${id}/step`,
      { id, step: 4, config, expectedRevision: revision },
    );
    expect(config.writeMode).toBe('CREATE_NEW');
    expect(config.targetDropTable).toBeUndefined();
    expect(request.post).toHaveBeenCalledTimes(1);
  });

  it('删除提交当前版本，服务端可以拒绝过期操作', async () => {
    await deleteWizard('90071992547409931', '90071992547409932');
    expect(request.delete).toHaveBeenCalledWith(
      '/dataforge/full-sync/wizard/90071992547409931',
      { params: { expectedRevision: '90071992547409932' } },
    );
  });

  it('连接与表选项沿用项目元数据契约', async () => {
    request.get.mockResolvedValueOnce([
      {
        id: '90071992547409931',
        name: 'ODS',
        databaseTypeName: 'Doris',
        databaseSchema: 'ods',
      },
    ]);
    expect(await getDatabaseOptions()).toEqual([
      {
        id: '90071992547409931',
        name: 'ODS',
        type: 'Doris',
        databaseName: 'ods',
      },
    ]);
    request.get.mockResolvedValueOnce([
      { tableName: 'source_table', remarks: '来源表' },
    ]);
    expect(await getDatabaseTables('90071992547409931')).toEqual([
      { name: 'source_table', comment: '来源表' },
    ]);
  });
});
