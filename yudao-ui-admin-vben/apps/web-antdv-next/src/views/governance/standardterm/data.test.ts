import { beforeEach, describe, expect, it, vi } from 'vitest';

import { formatTermUser, loadTermUserList, useFormSchema } from './data';

const state = vi.hoisted(() => ({
  access: { accessToken: 'session-a', refreshToken: 'refresh-a', loginExpired: false, accessCodes: ['governance:standard-term:query'] },
  user: { userInfo: { userId: '1' }, userRoles: ['reader'] },
  getUsers: vi.fn(),
  getTermCategories: vi.fn(),
  getElementCategories: vi.fn(),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => state.access, useUserStore: () => state.user }));
vi.mock('@vben/constants', () => ({ CommonStatusEnum: { ENABLE: 0 }, DICT_TYPE: { COMMON_STATUS: 'common_status' } }));
vi.mock('@vben/hooks', () => ({ getDictOptions: () => [] }));
vi.mock('#/adapter/form', () => ({ z: { number: () => ({ default: () => ({}) }) } }));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: state.getUsers }));
vi.mock('#/api/governance/categorydatameta', () => ({ getCategoryDatametaList: state.getElementCategories }));
vi.mock('#/api/governance/standardterm', () => ({ getStandardTermCategories: state.getTermCategories }));
vi.mock('#/api/governance/standardelement', () => ({ getStandardElementPage: vi.fn() }));


describe('业务术语类目与身份缓存', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.access.accessToken = 'session-a';
    state.user.userInfo.userId = '1';
  });

  it('编辑表单从各自的数据源加载术语类目和数据元类目，并保留大编号', async () => {
    state.getTermCategories.mockResolvedValue([
      { id: '9007199254740993', categoryName: '水务术语' },
      { id: '9007199254740994', parentId: '9007199254740993', categoryName: '河流' },
    ]);
    state.getElementCategories.mockResolvedValue([{ id: '9007199254741000', categoryName: '数据元' }]);
    const schemas = useFormSchema();
    const term = schemas.find((item) => item.fieldName === 'termCategoryId');
    const element = schemas.find((item) => item.fieldName === 'categoryId');
    const termTree = await (term!.componentProps as { api: () => Promise<any[]> }).api();
    const elementTree = await (element!.componentProps as { api: () => Promise<any[]> }).api();
    expect(termTree[0].id).toBe('9007199254740993');
    expect(termTree[0].children[0].id).toBe('9007199254740994');
    expect(elementTree[0].id).toBe('9007199254741000');
    expect(state.getTermCategories).toHaveBeenCalledOnce();
    expect(state.getElementCategories).toHaveBeenCalledOnce();
  });

  it('新会话不会接收旧会话迟到的用户信息', async () => {
    let resolveOld!: (value: unknown[]) => void;
    state.getUsers.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }));
    state.getUsers.mockResolvedValueOnce([{ id: '2', nickname: '新用户' }]);
    const old = loadTermUserList();
    state.access.accessToken = 'session-b';
    state.user.userInfo.userId = '2';
    await loadTermUserList();
    resolveOld([{ id: '1', nickname: '旧用户' }]);
    await old;
    expect(formatTermUser('1')).toBe('1');
    expect(formatTermUser('2')).toBe('新用户');
  });
});
