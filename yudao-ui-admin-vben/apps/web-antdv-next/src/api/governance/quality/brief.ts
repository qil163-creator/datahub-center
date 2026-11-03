import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityBriefApi {
  /** 简报模板附列 */
  export interface TemplateColumn {
    id?: number;
    fieldName?: string;
    colTitle?: string;
    dataField?: string;
    format?: string;
    sortNo?: number;
  }

  /** 简报模板段落 */
  export interface TemplateParagraph {
    id?: number;
    paraType?: string;
    sortNo?: number;
    title?: string;
    contentText?: string;
    ruleSetId?: number;
    columns?: TemplateColumn[];
  }

  /** 简报模板 */
  export interface Template {
    id?: number;
    templateName?: string;
    templateCode?: string;
    assessCycle?: string;
    assessDim?: string;
    sortNo?: number;
    status?: number;
    description?: string;
    paragraphs?: TemplateParagraph[];
    createTime?: string;
  }

  /** 质检简报 */
  export interface Brief {
    id?: number;
    briefTitle?: string;
    issueNo?: string;
    assessCycle?: string;
    assessStart?: string;
    assessEnd?: string;
    templateId?: number;
    templateName?: string;
    briefStatus?: string;
    briefContent?: string;
    generateTime?: string;
    generator?: string;
    createTime?: string;
  }

  /** 报告生成记录 */
  export interface ReportLog {
    id?: number;
    reportPeriodStart?: string;
    reportPeriodEnd?: string;
    generateStatus?: string;
    generateProgress?: number;
    generateMsg?: string;
    generator?: string;
    generateTime?: string;
    createTime?: string;
  }
}

// ==================== 简报模板 ====================

/** 简报模板分页 */
export function getBriefTemplatePage(
  params: PageParam & {
    assessCycle?: string;
    status?: number;
    templateName?: string;
  },
) {
  return requestClient.get<PageResult<QualityBriefApi.Template>>(
    '/governance/quality-brief-template/page',
    { params },
  );
}

/** 简报模板详情（含段落 + 附列） */
export function getBriefTemplate(id: number) {
  return requestClient.get<QualityBriefApi.Template>(
    `/governance/quality-brief-template/get?id=${id}`,
  );
}

/** 简报模板精简列表 */
export function getBriefTemplateSimpleList() {
  return requestClient.get<QualityBriefApi.Template[]>(
    '/governance/quality-brief-template/simple-list',
  );
}

/** 新增简报模板 */
export function createBriefTemplate(data: QualityBriefApi.Template) {
  return requestClient.post('/governance/quality-brief-template/create', data);
}

/** 修改简报模板 */
export function updateBriefTemplate(data: QualityBriefApi.Template) {
  return requestClient.put('/governance/quality-brief-template/update', data);
}

/** 删除简报模板 */
export function deleteBriefTemplate(id: number) {
  return requestClient.delete(
    `/governance/quality-brief-template/delete?id=${id}`,
  );
}

// ==================== 质检简报 ====================

/** 简报分页 */
export function getBriefPage(
  params: PageParam & {
    briefStatus?: string;
    briefTitle?: string;
    templateId?: number;
  },
) {
  return requestClient.get<PageResult<QualityBriefApi.Brief>>(
    '/governance/quality-brief/page',
    { params },
  );
}

/** 简报详情（含渲染内容） */
export function getBrief(id: number) {
  return requestClient.get<QualityBriefApi.Brief>(
    `/governance/quality-brief/get?id=${id}`,
  );
}

/** 生成简报 */
export function generateBrief(data: {
  assessEnd: string;
  assessStart: string;
  briefTitle: string;
  issueNo?: string;
  templateId: number;
}) {
  return requestClient.post<number>('/governance/quality-brief/generate', data);
}

/** 发布简报 */
export function publishBrief(id: number) {
  return requestClient.put(`/governance/quality-brief/publish?id=${id}`);
}

/** 删除简报 */
export function deleteBrief(id: number) {
  return requestClient.delete(`/governance/quality-brief/delete?id=${id}`);
}

/** 导出简报（WORD → .doc / EXCEL → .xls） */
export function exportBrief(id: number, format: 'EXCEL' | 'WORD') {
  return requestClient.download(
    `/governance/quality-brief/export?id=${id}&format=${format}`,
  );
}

/** 报告生成记录分页 */
export function getReportLogPage(
  params: PageParam & { generateStatus?: string; generator?: string },
) {
  return requestClient.get<PageResult<QualityBriefApi.ReportLog>>(
    '/governance/quality-brief/report-log/page',
    { params },
  );
}
