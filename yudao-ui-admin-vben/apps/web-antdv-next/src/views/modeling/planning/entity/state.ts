import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import eventIcon from '../reference-assets/entity-event.png';
import hierarchyIcon from '../reference-assets/entity-hierarchy.png';
import normalIcon from '../reference-assets/entity-object.png';
import processIcon from '../reference-assets/entity-process.png';

export const ENTITY_TYPES = {
  OBJECT: '业务对象',
  ACTIVITY: '业务活动',
} as const;
export const ENTITY_SUBTYPES = {
  NORMAL: {
    label: '普通对象',
    icon: normalIcon,
    description:
      '具有独立业务含义和属性，可单独识别、描述和管理的业务对象。例如：客户、商品、门店等。',
  },
  HIERARCHICAL: {
    label: '层级对象',
    icon: hierarchyIcon,
    description:
      '多个业务对象之间存在上下级关系，这一组对象就是层级对象。例如：行政区域中的国家、省、市、县。',
  },
  EVENT: {
    label: '业务事件',
    icon: eventIcon,
    description:
      '指业务过程中实际发生的一次行为或状态变化，通常会产生或更新业务数据。例如：下单、支付、退款、注册等。',
  },
  PROCESS: {
    label: '业务流程',
    icon: processIcon,
    description:
      '由一系列有先后顺序的业务事件组成，用于描述完整的业务过程。例如：订单处理、退款处理等。',
  },
} as const;
export const ENTITY_STATUSES = { 0: '上线', 1: '下线' } as const;

export function clearEntityRelations(values: BusinessEntityApi.SaveReq) {
  values.inheritEntityId = undefined;
  values.associatedEntityIds = [];
  values.predecessorEntityIds = [];
  values.processEventIds = [];
}
export function entitySavePayload(
  values: BusinessEntityApi.SaveReq,
): BusinessEntityApi.SaveReq {
  return {
    dataDomainId: values.dataDomainId,
    subjectDomainId: values.subjectDomainId,
    name: values.name.trim(),
    code: values.code.trim(),
    entityType: values.entityType,
    entitySubtype: values.entitySubtype,
    leaderUserId: values.leaderUserId || undefined,
    description: values.description,
    inheritEntityId:
      values.entitySubtype === 'NORMAL' ? values.inheritEntityId : undefined,
    associatedEntityIds:
      values.entitySubtype === 'HIERARCHICAL'
        ? []
        : [...values.associatedEntityIds],
    predecessorEntityIds:
      values.entitySubtype === 'PROCESS'
        ? [...values.predecessorEntityIds]
        : [],
    processEventIds:
      values.entitySubtype === 'PROCESS' ? [...values.processEventIds] : [],
  };
}
