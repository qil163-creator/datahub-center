import { beforeEach, describe, expect, it, vi } from 'vitest';

import { downloadMetaCollectInstanceLog } from '../metacollectinstance';
import {
  deleteMetaCollectTaskList,
  getMetaCollectScopeList,
  runMetaCollectTask,
  updateMetaCollectTaskScheduleEnabled,
} from '../metacollecttask';
import { deleteMetaFileList, getMetaFile, updateMetaFileBiz } from './index';

const mock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
  download: vi.fn(),
}));
vi.mock('#/api/request', () => ({ requestClient: mock }));
beforeEach(() => {
  vi.clearAllMocks();
});
const id = '9007199254740993';

describe('元数据接口线协议', () => {
  it('详情和来源库查询保持 ID 字符串', () => {
    getMetaFile(id);
    getMetaCollectScopeList(id);
    expect(mock.get).toHaveBeenCalledWith('/governance/meta-file/get', {
      params: { id },
    });
    expect(mock.get).toHaveBeenCalledWith(
      '/governance/meta-collect-task/scope-list',
      { params: { datasourceId: id } },
    );
  });
  it('运行和调度开关使用请求参数，未混入 body', () => {
    runMetaCollectTask(id);
    updateMetaCollectTaskScheduleEnabled(id, 1);
    expect(mock.post).toHaveBeenCalledWith(
      '/governance/meta-collect-task/run',
      undefined,
      { params: { id } },
    );
    expect(mock.put).toHaveBeenCalledWith(
      '/governance/meta-collect-task/update-schedule-enabled',
      undefined,
      { params: { id, scheduleEnabled: 1 } },
    );
  });
  it('批量删除保留各 ID，文件删除只调用 metadata 领域接口', () => {
    deleteMetaFileList([id, 7]);
    deleteMetaCollectTaskList([id]);
    expect(mock.delete).toHaveBeenCalledWith(
      '/governance/meta-file/delete-list',
      { params: { ids: `${id},7` } },
    );
    expect(mock.delete).toHaveBeenCalledWith(
      '/governance/meta-collect-task/delete-list',
      { params: { ids: id } },
    );
  });
  it('日志下载复用带认证请求客户端', () => {
    downloadMetaCollectInstanceLog(id);
    expect(mock.download).toHaveBeenCalledWith(
      '/governance/meta-collect-instance/log-download',
      { params: { instanceId: id } },
    );
  });
  it('业务属性提交携带读取到的修订号和人员字符串 ID', () => {
    const data = {
      id,
      bizRevision: 4,
      techLeaderUserId: id,
      description: '更新说明',
    };
    updateMetaFileBiz(data);
    expect(mock.put).toHaveBeenCalledWith(
      '/governance/meta-file/update-biz',
      data,
    );
  });
});
