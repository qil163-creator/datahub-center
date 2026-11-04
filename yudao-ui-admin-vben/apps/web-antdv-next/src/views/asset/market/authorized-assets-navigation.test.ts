import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  authorizedAssetLocation,
  parseAuthorizedApplyId,
  useAuthorizedAssetsNavigation,
} from './authorized-assets-navigation';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    loginExpired: false,
    accessCodes: ['asset:market:my-assets:query'],
  },
  user: { userInfo: { userId: '9007199254740993' } },
  hasRoute: vi.fn(),
  push: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.access.accessCodes.includes(code)),
  }),
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ hasRoute: mock.hasRoute, push: mock.push }),
}));
vi.mock('antdv-next', () => ({ message: { warning: mock.warning } }));
const ownApply = {
  id: '9223372036854775807',
  applyUserId: '9007199254740993',
  status: 2,
};
beforeEach(() => {
  vi.resetAllMocks();
  mock.access.accessToken = 'session';
  mock.access.loginExpired = false;
  mock.access.accessCodes = ['asset:market:my-assets:query'];
  mock.hasRoute.mockReturnValue(true);
  mock.push.mockResolvedValue(undefined);
});
describe('申请到本人授权的导航', () => {
  it.each([2, 5, 6])(
    '批准历史状态%s按菜单名称导航，与交付形态及当前effective无关',
    async (status) => {
      const navigation = useAuthorizedAssetsNavigation();
      const row = { ...ownApply, status, assetType: 2, effective: false };
      expect(navigation.canOpen(row)).toBe(true);
      expect(await navigation.open(row)).toBe(true);
      expect(mock.push).toHaveBeenCalledWith({
        name: 'AssetMarketMyAssets',
        query: { applyId: '9223372036854775807' },
      });
    },
  );
  it('没有个人消费权限、非本人和未批准申请均不跳转', async () => {
    const navigation = useAuthorizedAssetsNavigation();
    for (const status of [0, 1, 3, 4, 8])
      expect(navigation.canOpen({ ...ownApply, status })).toBe(false);
    expect(
      navigation.canOpen({ ...ownApply, applyUserId: '9007199254740994' }),
    ).toBe(false);
    expect(navigation.canOpen({ ...ownApply, applyUserId: undefined })).toBe(
      false,
    );
    mock.access.accessCodes = ['asset:market:query'];
    expect(await navigation.open(ownApply)).toBe(false);
    mock.access.accessCodes = ['asset:market:my-assets:query'];
    mock.access.loginExpired = true;
    expect(await navigation.open(ownApply)).toBe(false);
    expect(mock.push).not.toHaveBeenCalled();
  });
  it('菜单缺失提示刷新而不猜测旧路径', async () => {
    mock.hasRoute.mockReturnValue(false);
    const navigation = useAuthorizedAssetsNavigation();
    expect(await navigation.open(ownApply)).toBe(false);
    expect(mock.warning).toHaveBeenCalled();
    expect(mock.push).not.toHaveBeenCalled();
  });
  it('守卫取消及路由加载失败都不报告跳转成功', async () => {
    const navigation = useAuthorizedAssetsNavigation();
    mock.push.mockResolvedValueOnce({ type: 4 });
    expect(await navigation.open(ownApply)).toBe(false);
    mock.push.mockRejectedValueOnce(new Error('chunk unavailable'));
    expect(await navigation.open(ownApply)).toBe(false);
    expect(mock.warning).toHaveBeenCalledTimes(2);
  });
  it('拒绝非法和超界ID，19位Long保持字符串', () => {
    for (const value of [
      undefined,
      null,
      '',
      '0',
      '-1',
      '01',
      ['1'],
      Number.MAX_SAFE_INTEGER + 1,
      '9223372036854775808',
      '9'.repeat(10_000),
    ])
      expect(parseAuthorizedApplyId(value)).toBeUndefined();
    expect(parseAuthorizedApplyId('9223372036854775807')).toBe(
      '9223372036854775807',
    );
    expect(
      authorizedAssetLocation(
        { ...ownApply, id: '0' },
        ownApply.applyUserId,
        true,
      ),
    ).toBeUndefined();
  });
});
