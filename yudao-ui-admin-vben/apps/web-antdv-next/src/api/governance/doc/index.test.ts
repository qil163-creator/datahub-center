import { beforeEach, describe, expect, it, vi } from 'vitest';

import { requestClient } from '#/api/request';

import {
  createDoc,
  createDocRegistration,
  getDoc,
  getDocCategories,
  getDocPage,
  getDocSimpleList,
  getDocVersions,
  updateDocRegistration,
} from './index';

vi.mock('#/api/request', () => ({
  requestClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
}));
beforeEach(() => vi.resetAllMocks());
describe('标准登记Long传输', () => {
  it('详情、替代候选及版本请求保留Long上限', async () => {
    const id = '9223372036854775807';
    vi.mocked(requestClient.get).mockResolvedValue({ id });
    expect(await getDoc(id)).toEqual({ id });
    expect(requestClient.get).toHaveBeenCalledWith(
      `/governance/standard-doc/get?id=${id}`,
    );
    await getDocSimpleList(id);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/standard-doc/simple-list',
      { params: { excludeId: id } },
    );
    await getDocVersions(id);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/standard-doc/versions',
      { params: { id } },
    );
  });
});

describe('标准检索与登记入口隔离', () => {
  it('旧创建路径保持不变，完整登记使用独立路径并保留日期和Long', async () => {
    const base = {
      nameCn: '标准',
      stdType: 'NATIONAL',
      implementStatus: 'DRAFT',
      status: 0,
      sort: 0,
    };
    await createDoc(base);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/standard-doc/create',
      base,
    );
    const data = {
      ...base,
      categoryId: '9223372036854775807',
      fileId: '9007199254740993',
      stdCode: 'GB-1',
      abolishDate: '2027-10-19',
    };
    await createDocRegistration(data);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/governance/standard-doc/registration/create',
      data,
    );
    await updateDocRegistration({ ...data, id: '9007199254740994' });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/governance/standard-doc/registration/update',
      { ...data, id: '9007199254740994' },
    );
  });
  it('类别筛选和标准类型以原始值发送，类目使用文档授权端点', async () => {
    const params = {
      categoryId: '9223372036854775807',
      stdType: 'INNER',
      pageNo: 2,
      pageSize: 10,
    };
    await getDocPage(params);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/standard-doc/page',
      { params },
    );
    await getDocCategories();
    expect(requestClient.get).toHaveBeenCalledWith(
      '/governance/standard-doc/categories',
    );
  });
});
