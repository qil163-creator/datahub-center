import { describe, expect, it } from 'vitest';

import {
  buildMetricCategoryTree,
  indicatorDetailLocation,
  indicatorPageParams,
} from './state';

describe('指标列表导航与筛选契约', () => {
  it('没有模型的真实主题也可以筛选，不伪造其他板块的归属', () => {
    const root = buildMetricCategoryTree([{ id: '1', name: '水利' }], [
      { id: '11', dataDomainId: '1', name: '水文监测' },
      { id: '12', dataDomainId: '2', name: '交易主题' },
    ], [], '原子指标类目')[0]!;
    expect(root.children[0]!.children.map((item) => item.title)).toEqual(['水文监测']);
    expect(root.children[0]!.children[0]!.filter).toEqual({ dataDomainId: '1', subjectDomainId: '11' });
  });
  it('域、主题与来源由真实模型归属建立，搜索保留命中路径', () => {
    const tree = buildMetricCategoryTree(
      [
        { id: '1', name: '交易域' },
        { id: '2', name: '监管域' },
      ],
      [{ id: '11', name: '订单主题' }],
      [
        {
          id: '9223372036854775806',
          name: '订单事实表',
          dataDomainId: '1',
          subjectDomainId: '11',
        },
      ],
      '原子指标类目',
      '订单事实',
    );
    expect(tree[0]!.children).toHaveLength(1);
    expect(tree[0]!.children[0]!.children[0]!.children[0]!.filter).toEqual({
      dataDomainId: '1',
      subjectDomainId: '11',
      sourceModelId: '9223372036854775806',
    });
  });
  it('树选择优先于顶部来源筛选，并保留状态、页码和无损编号', () => {
    expect(
      indicatorPageParams({
        pageNo: 2,
        pageSize: 10,
        keyword: ' 支付 ',
        indicatorType: 'ATOMIC',
        sourceModelId: '3',
        status: 'DRAFT',
        filter: { sourceModelId: '9223372036854775806', dataDomainId: '1' },
      }),
    ).toEqual({
      pageNo: 2,
      pageSize: 10,
      keyword: '支付',
      indicatorType: 'ATOMIC',
      sourceModelId: '9223372036854775806',
      dataDomainId: '1',
      status: 'DRAFT',
    });
  });
  it('按真实指标类型进入完整页详情，返回原列表入口', () => {
    expect(
      indicatorDetailLocation(
        { id: '9223372036854775806', indicatorType: 'ATOMIC' },
        '/model/indicator-model/atomic',
      ),
    ).toEqual({
      name: 'ModelingAtomicIndicatorDetail',
      params: { id: '9223372036854775806' },
      query: { listPath: '/model/indicator-model/atomic' },
    });
    expect(
      indicatorDetailLocation({ id: 1, indicatorType: 'DERIVED' }, '/legacy')
        .name,
    ).toBe('ModelingDerivedIndicatorDetail');
  });
});
