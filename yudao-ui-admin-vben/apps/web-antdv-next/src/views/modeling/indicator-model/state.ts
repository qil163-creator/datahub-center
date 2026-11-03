import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

type Id = IndicatorModelApi.Id;
export interface MetricFilter {
  dataDomainId?: Id;
  sourceModelId?: Id;
  subjectDomainId?: Id;
}
export interface MetricTreeNode {
  children: MetricTreeNode[];
  filter: MetricFilter;
  key: string;
  level: 'all' | 'domain' | 'model' | 'subject';
  title: string;
}

/** 优先使用主题的真实板块归属；来源模型补齐其引用的主题路径。 */
export function buildMetricCategoryTree(
  domains: { id?: Id; name?: string }[],
  subjects: { dataDomainId?: Id; id?: Id; name?: string }[],
  models: {
    code?: string;
    dataDomainId?: Id;
    id: Id;
    name?: string;
    subjectDomainId?: Id;
  }[],
  title: string,
  keyword = '',
): MetricTreeNode[] {
  const root: MetricTreeNode = {
    key: 'all',
    title,
    level: 'all',
    filter: {},
    children: [],
  };
  for (const domain of domains) {
    if (domain.id === undefined) continue;
    const node: MetricTreeNode = {
      key: `domain:${domain.id}`,
      title: domain.name || String(domain.id),
      level: 'domain',
      filter: { dataDomainId: domain.id },
      children: [],
    };
    for (const subject of subjects) {
      if (
        subject.id === undefined ||
        subject.dataDomainId === undefined ||
        String(subject.dataDomainId) !== String(domain.id)
      )
        continue;
      node.children.push({
        key: `subject:${domain.id}:${subject.id}`,
        title: subject.name || String(subject.id),
        level: 'subject',
        filter: { dataDomainId: domain.id, subjectDomainId: subject.id },
        children: [],
      });
    }
    for (const model of models.filter(
      (item) => String(item.dataDomainId) === String(domain.id),
    )) {
      const modelNode: MetricTreeNode = {
        key: `model:${model.id}`,
        title: model.name || model.code || String(model.id),
        level: 'model',
        filter: {
          dataDomainId: domain.id,
          subjectDomainId: model.subjectDomainId,
          sourceModelId: model.id,
        },
        children: [],
      };
      if (model.subjectDomainId === undefined) {
        node.children.push(modelNode);
        continue;
      }
      const key = `subject:${domain.id}:${model.subjectDomainId}`;
      let subject = node.children.find((item) => item.key === key);
      if (!subject) {
        subject = {
          key,
          title:
            subjects.find(
              (item) => String(item.id) === String(model.subjectDomainId),
            )?.name || String(model.subjectDomainId),
          level: 'subject',
          filter: {
            dataDomainId: domain.id,
            subjectDomainId: model.subjectDomainId,
          },
          children: [],
        };
        node.children.push(subject);
      }
      subject.children.push(modelNode);
    }
    root.children.push(node);
  }
  const text = keyword.trim().toLowerCase();
  function search(node: MetricTreeNode): MetricTreeNode | undefined {
    if (!text || node.title.toLowerCase().includes(text)) return node;
    const children = node.children
      .map(search)
      .filter((item): item is MetricTreeNode => !!item);
    return children.length > 0 ? { ...node, children } : undefined;
  }
  return [search(root)].filter((item): item is MetricTreeNode => !!item);
}

/** 详情导航保留无损编号和实际列表入口。 */
export function indicatorDetailLocation(
  row: Pick<IndicatorModelApi.Model, 'id' | 'indicatorType'>,
  listPath: string,
) {
  return {
    name:
      row.indicatorType === 'ATOMIC'
        ? 'ModelingAtomicIndicatorDetail'
        : 'ModelingDerivedIndicatorDetail',
    params: { id: String(row.id) },
    query: { listPath },
  };
}

export function indicatorPageParams(input: {
  filter: MetricFilter;
  indicatorType: IndicatorModelApi.Type;
  keyword: string;
  pageNo: number;
  pageSize: number;
  sourceModelId?: Id;
  status?: IndicatorModelApi.Status;
}): IndicatorModelApi.PageReq {
  return {
    pageNo: input.pageNo,
    pageSize: input.pageSize,
    indicatorType: input.indicatorType,
    keyword: input.keyword.trim() || undefined,
    status: input.status,
    ...input.filter,
    sourceModelId: input.filter.sourceModelId ?? input.sourceModelId,
  };
}
