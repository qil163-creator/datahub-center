<script lang="ts" setup>
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { QualityBriefApi } from '#/api/governance/quality/brief';

import { reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';
import { downloadFileFromBlobPart } from '@vben/utils';

import {
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Segmented,
  Select,
  Tag,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  deleteBrief,
  deleteBriefTemplate,
  exportBrief,
  generateBrief,
  getBriefPage,
  getBriefTemplatePage,
  getBriefTemplateSimpleList,
  getReportLogPage,
  publishBrief,
} from '#/api/governance/quality/brief';
import { $t } from '#/locales';

import BriefPreview from './modules/preview.vue';
import TemplateForm from './modules/template-form.vue';

defineOptions({ name: 'GovernanceQualityBrief' });

const activeTab = ref('brief');
const viewOptions = [
  { label: '简报管理', value: 'brief' },
  { label: '简报模板', value: 'template' },
  { label: '报告记录', value: 'log' },
];
const CYCLE_LABELS: Record<string, string> = {
  DAY: '日度',
  WEEK: '周度',
  MONTH: '月度',
};

const templateFormRef = ref<InstanceType<typeof TemplateForm>>();
const previewRef = ref<InstanceType<typeof BriefPreview>>();

const generateVisible = ref(false);
const generateSaving = ref(false);
const generateForm = reactive<{
  assessEnd?: string;
  assessStart?: string;
  briefTitle?: string;
  issueNo?: string;
  templateId?: number;
}>({});
const templateOptions = ref<QualityBriefApi.Template[]>([]);

function handleRefresh() {
  briefGridApi.query();
  templateGridApi.query();
  logGridApi.query();
}

async function openGenerate() {
  Object.keys(generateForm).forEach((key) =>
    Reflect.deleteProperty(generateForm, key),
  );
  templateOptions.value = await getBriefTemplateSimpleList();
  generateVisible.value = true;
}

async function handleGenerate() {
  if (
    !generateForm.templateId ||
    !generateForm.briefTitle ||
    !generateForm.assessStart ||
    !generateForm.assessEnd
  ) {
    message.warning('请完整填写模板、标题与评估周期');
    return;
  }
  generateSaving.value = true;
  try {
    await generateBrief(generateForm as any);
    message.success('简报生成成功');
    generateVisible.value = false;
    handleRefresh();
  } finally {
    generateSaving.value = false;
  }
}

async function handlePublish(row: QualityBriefApi.Brief) {
  await publishBrief(row.id!);
  message.success('发布成功');
  handleRefresh();
}

async function handleExport(
  row: QualityBriefApi.Brief,
  format: 'EXCEL' | 'WORD',
) {
  const data = await exportBrief(row.id!, format);
  downloadFileFromBlobPart({
    fileName: `${row.briefTitle}${format === 'WORD' ? '.doc' : '.xls'}`,
    source: data,
  });
}

async function handleDeleteBrief(row: QualityBriefApi.Brief) {
  await deleteBrief(row.id!);
  handleRefresh();
}

async function handleDeleteTemplate(row: QualityBriefApi.Template) {
  await deleteBriefTemplate(row.id!);
  handleRefresh();
}

const [BriefGrid, briefGridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'briefTitle',
        label: '简报标题',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入简报标题' },
      },
      {
        fieldName: 'briefStatus',
        label: '状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: [
            { label: '草稿', value: 'DRAFT' },
            { label: '已发布', value: 'PUBLISHED' },
          ],
          placeholder: '请选择状态',
        },
      },
    ],
  },
  gridOptions: {
    columns: [
      { field: 'id', title: '编号', width: 70 },
      { field: 'briefTitle', title: '简报标题', minWidth: 180, align: 'left' },
      { field: 'issueNo', title: '期号', width: 80 },
      {
        field: 'assessStart',
        title: '评估周期',
        width: 150,
        slots: { default: 'period' },
      },
      { field: 'templateName', title: '模板', width: 140 },
      {
        field: 'briefStatus',
        title: '状态',
        width: 90,
        slots: { default: 'briefStatus' },
      },
      {
        field: 'generateTime',
        title: '生成时间',
        width: 165,
        formatter: 'formatDateTime',
      },
      { field: 'generator', title: '生成人', width: 100 },
      {
        title: '操作',
        width: 300,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getBriefPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityBriefApi.Brief>,
});

const [TemplateGrid, templateGridApi] = useVbenVxeGrid({
  formOptions: {
    schema: [
      {
        fieldName: 'templateName',
        label: '模板名称',
        component: 'Input',
        componentProps: { allowClear: true, placeholder: '请输入模板名称' },
      },
    ],
  },
  gridOptions: {
    columns: [
      { field: 'id', title: '编号', width: 70 },
      {
        field: 'templateName',
        title: '模板名称',
        minWidth: 180,
        align: 'left',
      },
      { field: 'templateCode', title: '编码', width: 130 },
      {
        field: 'assessCycle',
        title: '评估周期',
        width: 90,
        formatter: ({ cellValue }) => CYCLE_LABELS[cellValue] ?? '-',
      },
      { field: 'assessDim', title: '评估维度', width: 100 },
      {
        field: 'status',
        title: '状态',
        width: 80,
        slots: { default: 'status' },
      },
      {
        field: 'createTime',
        title: '创建时间',
        width: 165,
        formatter: 'formatDateTime',
      },
      {
        title: '操作',
        width: 180,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          return await getBriefTemplatePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<QualityBriefApi.Template>,
});

const [LogGrid, logGridApi] = useVbenVxeGrid({
  gridOptions: {
    columns: [
      { field: 'id', title: '编号', width: 70 },
      {
        field: 'reportPeriodStart',
        title: '报告周期',
        width: 170,
        slots: { default: 'period' },
      },
      {
        field: 'generateStatus',
        title: '生成状态',
        width: 110,
        slots: { default: 'generateStatus' },
      },
      { field: 'generateMsg', title: '生成信息', minWidth: 220, align: 'left' },
      { field: 'generator', title: '生成人员', width: 100 },
      {
        field: 'generateTime',
        title: '生成时间',
        width: 165,
        formatter: 'formatDateTime',
      },
    ],
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }) => {
          return await getReportLogPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
          });
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: false },
  } as VxeTableGridOptions<QualityBriefApi.ReportLog>,
});
</script>

<template>
  <Page auto-content-height>
    <template #extra>
      <Segmented v-model:value="activeTab" :options="viewOptions" />
    </template>
    <BriefGrid v-if="activeTab === 'brief'" table-title="简报管理">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: '生成简报',
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:quality-brief:create'],
              onClick: openGenerate,
            },
          ]"
        />
      </template>
      <template #period="{ row }">
        {{ row.assessStart }} ~ {{ row.assessEnd }}
      </template>
      <template #briefStatus="{ row }">
        <Tag :color="row.briefStatus === 'PUBLISHED' ? 'success' : 'default'">
          {{ row.briefStatus === 'PUBLISHED' ? '已发布' : '草稿' }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '预览',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              onClick: () => {
                if (row.id != null) {
                  previewRef?.open(row.id);
                }
              },
            },
          ]"
          :drop-down-actions="[
            {
              label: '导出Word',
              type: 'link',
              icon: ACTION_ICON.DOWNLOAD,
              onClick: handleExport.bind(null, row, 'WORD'),
            },
            {
              label: '导出Excel',
              type: 'link',
              icon: ACTION_ICON.DOWNLOAD,
              onClick: handleExport.bind(null, row, 'EXCEL'),
            },
            {
              label: '发布',
              type: 'link',
              icon: 'lucide:send',
              auth: ['governance:quality-brief:update'],
              ifShow: row.briefStatus !== 'PUBLISHED',
              onClick: handlePublish.bind(null, row),
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:quality-brief:delete'],
              popConfirm: {
                title: `确认删除简报「${row.briefTitle}」吗？`,
                confirm: handleDeleteBrief.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </BriefGrid>
    <TemplateGrid v-else-if="activeTab === 'template'" table-title="简报模板">
      <template #toolbar-tools>
        <TableAction
          :actions="[
            {
              label: $t('common.create'),
              type: 'primary',
              icon: ACTION_ICON.ADD,
              auth: ['governance:quality-brief:create'],
              onClick: () => templateFormRef?.open(),
            },
          ]"
        />
      </template>
      <template #status="{ row }">
        <Tag :color="row.status === 0 ? 'success' : 'default'">
          {{ row.status === 0 ? '启用' : '停用' }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '配置',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:quality-brief:update'],
              onClick: () => {
                if (row.id != null) {
                  templateFormRef?.open(row.id);
                }
              },
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:quality-brief:delete'],
              popConfirm: {
                title: `确认删除简报模板「${row.templateName}」吗？`,
                confirm: handleDeleteTemplate.bind(null, row),
              },
            },
          ]"
        />
      </template>
    </TemplateGrid>
    <LogGrid v-else table-title="报告记录">
      <template #period="{ row }">
        {{ row.reportPeriodStart }} ~ {{ row.reportPeriodEnd }}
      </template>
      <template #generateStatus="{ row }">
        <Tag
          :color="
            row.generateStatus === 'SUCCESS'
              ? 'success'
              : row.generateStatus === 'FAILED'
                ? 'error'
                : 'processing'
          "
        >
          {{
            row.generateStatus === 'SUCCESS'
              ? '已生成'
              : row.generateStatus === 'FAILED'
                ? '生成失败'
                : '生成中'
          }}
        </Tag>
      </template>
    </LogGrid>

    <TemplateForm ref="templateFormRef" @saved="handleRefresh" />
    <BriefPreview ref="previewRef" />

    <Modal
      v-model:open="generateVisible"
      :confirm-loading="generateSaving"
      :width="800"
      title="生成简报"
      @ok="handleGenerate"
    >
      <Form
        :label-col="{ style: { width: '130px' } }"
        :wrapper-col="{ style: { flex: '1 1 0' } }"
      >
        <FormItem label="简报模板" required>
          <Select
            v-model:value="generateForm.templateId"
            :options="
              templateOptions.map((tpl) => ({
                label: tpl.templateName,
                value: tpl.id,
              }))
            "
            placeholder="请选择简报模板"
          />
        </FormItem>
        <FormItem label="简报标题" required>
          <Input
            v-model:value="generateForm.briefTitle"
            placeholder="请输入简报标题"
          />
        </FormItem>
        <FormItem label="期号">
          <Input v-model:value="generateForm.issueNo" placeholder="如 001" />
        </FormItem>
        <FormItem label="评估开始" required>
          <Input
            v-model:value="generateForm.assessStart"
            placeholder="如 202609"
          />
        </FormItem>
        <FormItem label="评估结束" required>
          <Input
            v-model:value="generateForm.assessEnd"
            placeholder="如 202609"
          />
        </FormItem>
        <div class="text-muted-foreground text-xs">
          如果所选周期没有评分/检测数据，正文变量将按空数据渲染
        </div>
      </Form>
    </Modal>
  </Page>
</template>
