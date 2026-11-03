import type { BusinessConditionApi } from '#/api/modeling/business-condition';

import { describe, expect, it } from 'vitest';

import {
  conditionCloneData,
  conditionRouteId,
  conditionSaveData,
  conditionSourceMatches,
  conditionValidation,
} from './data';

const detail: BusinessConditionApi.Model = {
  id: '9223372036854775806',
  revision: '9007199254740993',
  code: 'PAID_ORDER',
  name: '已支付订单',
  dataDomainId: '9223372036854775805',
  subjectDomainId: '9223372036854775800',
  entityId: '9223372036854775799',
  ownerUserId: '9223372036854775798',
  dataFreshness: '1',
  sourceModelId: '9223372036854775804',
  configType: 'BASIC',
  logicMode: 'UNIFIED',
  expression: "pay_status = 'PAID'",
  status: 'ACTIVE',
  creatorName: '创建人',
  createTime: 1,
};

describe('业务限定写入契约', () => {
  it('修改使用重新读取详情的无损编号和修订号', () => {
    const result = conditionSaveData(
      {
        ...detail,
        id: '1',
        expectedRevision: 1,
        code: ' PAID_ORDER ',
        name: ' 已支付订单 ',
      },
      detail,
    );
    expect(result).toMatchObject({
      id: detail.id,
      expectedRevision: detail.revision,
      dataDomainId: detail.dataDomainId,
      sourceModelId: detail.sourceModelId,
      code: 'PAID_ORDER',
      name: '已支付订单',
    });
    expect(result.status).toBe('ACTIVE');
    expect(result).not.toHaveProperty('revision');
    expect(result).not.toHaveProperty('creatorName');
    expect(result).not.toHaveProperty('createTime');
  });

  it('克隆清空编号、修订号、编码与发布状态，并保留来源定义', () => {
    const cloned = conditionCloneData(detail);
    expect(cloned.id).toBeUndefined();
    expect(cloned.expectedRevision).toBeUndefined();
    expect(cloned.code).toBe('');
    expect(cloned.status).toBe('DRAFT');
    expect(cloned).not.toHaveProperty('creatorName');
    expect(cloned.sourceModelId).toBe(detail.sourceModelId);
    expect(cloned.expression).toBe(detail.expression);
    expect(conditionValidation(cloned, true)).toContain('英文名');
  });

  it('新建忽略客户端携带的旧编号和修订号', () => {
    const result = conditionSaveData(detail);
    expect(result.id).toBeUndefined();
    expect(result.expectedRevision).toBeUndefined();
    expect(result.status).toBe('ACTIVE');
  });

  it('仅接收有效十进制路由编号且不经过数值转换', () => {
    expect(conditionRouteId(detail.id)).toBe(detail.id);
    for (const value of ['0', '-1', '1.5', '1e20', ' 1', ['1'], undefined])
      expect(conditionRouteId(value)).toBeUndefined();
  });

  it('下一步验证基础字段，定义步骤验证来源和表达式', () => {
    expect(conditionValidation({ ...detail, expression: '' })).toBeUndefined();
    expect(
      conditionValidation({ ...detail, sourceModelId: '' }, true),
    ).toContain('来源');
    expect(
      conditionValidation({ ...detail, expression: '  ' }, true),
    ).toContain('计算逻辑');
    expect(conditionValidation(detail, true)).toBeUndefined();
  });

  it('来源必须启用且已发布，归属实体时效一致', () => {
    const source = {
      dataDomainId: detail.dataDomainId,
      subjectDomainId: detail.subjectDomainId,
      entityId: detail.entityId,
      dataTimeliness: detail.dataFreshness,
      enabled: 1,
      tableType: 'DETAIL' as const,
      status: 'PUBLISHED' as const,
    };
    expect(conditionSourceMatches(source, detail)).toBe(true);
    expect(conditionSourceMatches({ ...source, enabled: 0 }, detail)).toBe(
      false,
    );
    expect(
      conditionSourceMatches({ ...source, status: 'DISCARDED' }, detail),
    ).toBe(false);
    expect(
      conditionSourceMatches({ ...source, status: 'DRAFT' }, detail),
    ).toBe(false);
    expect(conditionSourceMatches({ ...source, entityId: '1' }, detail)).toBe(
      false,
    );
    expect(
      conditionSourceMatches({ ...source, dataTimeliness: '2' }, detail),
    ).toBe(false);
    expect(
      conditionSourceMatches({ ...source, subjectDomainId: '3' }, detail),
    ).toBe(false);
  });

  it('拒绝当前尚未支持的组合或分时段定义', () => {
    expect(
      conditionValidation({ ...detail, configType: 'COMPOSITE' }, true),
    ).toContain('仅支持');
    expect(
      conditionValidation({ ...detail, logicMode: 'SEGMENTED' }, true),
    ).toContain('仅支持');
  });

  it('与服务端的业务实体、描述及表达式长度边界保持一致', () => {
    expect(
      conditionValidation(
        {
          ...detail,
          businessEntity: 'a'.repeat(100),
          description: 'a'.repeat(2000),
          expression: 'a'.repeat(4000),
        },
        true,
      ),
    ).toBeUndefined();
    expect(
      conditionValidation({ ...detail, businessEntity: 'a'.repeat(101) }, true),
    ).toContain('100');
    expect(
      conditionValidation({ ...detail, description: 'a'.repeat(2001) }, true),
    ).toContain('2000');
    expect(
      conditionValidation({ ...detail, expression: 'a'.repeat(4001) }, true),
    ).toContain('4000');
  });
});
