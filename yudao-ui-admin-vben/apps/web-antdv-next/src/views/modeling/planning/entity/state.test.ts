import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import { describe, expect, it } from 'vitest';

import { entitySavePayload } from './state';

function entity(): BusinessEntityApi.SaveReq {
  return { dataDomainId: '9007199254740995', subjectDomainId: '9007199254740997', name: ' 流程 ', code: ' FLOW ', entityType: 'ACTIVITY', entitySubtype: 'PROCESS', description: '', inheritEntityId: '9007199254740999', associatedEntityIds: ['9007199254741001'], predecessorEntityIds: ['9007199254741003'], processEventIds: ['9007199254741007', '9007199254741005'] };
}
describe('业务实体提交边界', () => {
  it('流程保留事件顺序及无损关系编号，排除不适用的继承字段', () => {
    const result = entitySavePayload(entity());
    expect(result.dataDomainId).toBe('9007199254740995');
    expect(result.processEventIds).toEqual(['9007199254741007', '9007199254741005']);
    expect(result.inheritEntityId).toBeUndefined();
    expect(result.name).toBe('流程');
    expect(result.code).toBe('FLOW');
  });
  it('从流程切为层级对象时不会提交隐藏的旧关系', () => {
    const result = entitySavePayload({ ...entity(), entityType: 'OBJECT', entitySubtype: 'HIERARCHICAL' });
    expect(result.inheritEntityId).toBeUndefined();
    expect(result.associatedEntityIds).toEqual([]);
    expect(result.predecessorEntityIds).toEqual([]);
    expect(result.processEventIds).toEqual([]);
  });
  it('普通对象保留继承及关联，业务事件仅保留关联', () => {
    const object = entitySavePayload({ ...entity(), entityType: 'OBJECT', entitySubtype: 'NORMAL' });
    const event = entitySavePayload({ ...entity(), entitySubtype: 'EVENT' });
    expect(object.inheritEntityId).toBe('9007199254740999');
    expect(event.inheritEntityId).toBeUndefined();
    expect(event.associatedEntityIds).toEqual(['9007199254741001']);
    expect(event.predecessorEntityIds).toEqual([]);
    expect(event.processEventIds).toEqual([]);
  });
});
