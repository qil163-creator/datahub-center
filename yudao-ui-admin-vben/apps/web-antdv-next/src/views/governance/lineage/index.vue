<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceLineageApi } from '#/api/governance/lineage';

import { onBeforeUnmount, ref } from 'vue';

import { Page } from '@vben/common-ui';

import { Alert, Button, Empty, Select, Table, TabPane, Tabs, Tag, Tooltip } from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import { getLineageImpact, getLineageNodeColumns, getLineageSourcePage } from '#/api/governance/lineage';

import LineageExplorer from './components/lineage-explorer.vue';
import LineageNodeSelect from './components/lineage-node-select.vue';
import { useSourceColumns, useSourceFormSchema } from './data';
import { nodeOptionLabel, SOURCE_TYPE_LABELS, STATUS_LABELS } from './graph-model';

defineOptions({ name: 'GovernanceLineage' });

const activeTab = ref('graph');

// ==================== 血缘图 ====================
const graphNodeId = ref<string>();

// ==================== 影响分析 ====================
const impactNodeId = ref<string>();
const impactColumn = ref<string>();
const impactColumns = ref<string[]>([]);
const impactDepth = ref(5);
const impact = ref<GovernanceLineageApi.Impact>();
const impactLoading = ref(false);
const impactError = ref('');
let impactController: AbortController | undefined;
let columnsController: AbortController | undefined;

const impactColumnsDef = [
  { title: '层级', dataIndex: 'depth', width: 70 },
  { title: '下游对象', key: 'node' },
  { title: '字段', dataIndex: 'column', width: 160 },
  { title: '产生该依赖的任务', key: 'sources' },
];

async function onImpactNodeChange() {
  impactColumn.value = undefined;
  impactColumns.value = [];
  impact.value = undefined;
  columnsController?.abort();
  const nodeId = impactNodeId.value;
  if (!nodeId) return;
  const controller = new AbortController();
  columnsController = controller;
  try {
    const list = await getLineageNodeColumns(nodeId, controller.signal);
    if (controller === columnsController) impactColumns.value = (list ?? []).filter((name) => name !== '*');
  } catch {
    if (controller === columnsController) impactColumns.value = [];
  }
}

async function runImpact() {
  const nodeId = impactNodeId.value;
  if (!nodeId) return;
  impactController?.abort();
  const controller = new AbortController();
  impactController = controller;
  impactLoading.value = true;
  impactError.value = '';
  try {
    const result = await getLineageImpact(
      { nodeId, column: impactColumn.value, depth: impactDepth.value },
      controller.signal,
    );
    if (controller === impactController) impact.value = result;
  } catch {
    if (controller === impactController) {
      impactError.value = '影响分析失败，请稍后重试';
      impact.value = undefined;
    }
  } finally {
    if (controller === impactController) impactLoading.value = false;
  }
}

onBeforeUnmount(() => {
  impactController?.abort();
  columnsController?.abort();
});

// ==================== 解析记录 ====================
const [SourceGrid] = useVbenVxeGrid({
  formOptions: { schema: useSourceFormSchema() },
  gridOptions: {
    columns: useSourceColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) =>
          await getLineageSourcePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
          }),
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<GovernanceLineageApi.Source>,
});
</script>

<template>
  <Page auto-content-height>
    <Tabs v-model:active-key="activeTab" class="lineage-page">
      <TabPane key="graph" tab="血缘图">
        <div class="lineage-page__filter">
          <LineageNodeSelect v-model:value="graphNodeId" />
          <span class="lineage-page__hint">
            血缘由开发任务、数据同步、集成任务和可视化 ETL 自动解析上报，任务保存或运行结束后约一分钟内更新。
          </span>
        </div>
        <div class="lineage-page__explorer">
          <LineageExplorer v-if="graphNodeId" :node-id="graphNodeId" />
          <Empty v-else description="请选择一张表或外部数据集查看血缘" />
        </div>
      </TabPane>
      <TabPane key="impact" tab="影响分析">
        <div class="lineage-page__filter">
          <LineageNodeSelect v-model:value="impactNodeId" @change="onImpactNodeChange" />
          <Select
            v-model:value="impactColumn"
            :options="impactColumns.map((name) => ({ label: name, value: name }))"
            allow-clear
            placeholder="字段（不选则分析整表）"
            style="width: 220px"
            :disabled="!impactNodeId"
          />
          <Select
            v-model:value="impactDepth"
            :options="[1, 3, 5, 10].map((value) => ({ label: `${value} 层`, value }))"
            style="width: 100px"
            aria-label="分析层数"
          />
          <Button type="primary" :disabled="!impactNodeId" :loading="impactLoading" @click="runImpact">
            分析下游影响
          </Button>
        </div>
        <Alert v-if="impactError" type="error" show-icon :message="impactError" />
        <Alert
          v-if="impact?.truncated"
          type="warning"
          show-icon
          message="影响范围较大，仅展示前 500 项。"
        />
        <Table
          v-if="impact"
          :columns="impactColumnsDef"
          :data-source="impact.items"
          :row-key="(row: GovernanceLineageApi.ImpactItem) => `${String(row.nodeId)}:${row.column ?? ''}`"
          :pagination="{ pageSize: 20 }"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'node'">
              {{ record.node ? nodeOptionLabel(record.node) : record.nodeId }}
              <Tag v-if="record.node?.nodeType === 'EXTERNAL'" color="orange">外部</Tag>
            </template>
            <template v-else-if="column.key === 'sources'">
              <Tag v-for="source in record.sources" :key="String(source.id)">
                {{ SOURCE_TYPE_LABELS[source.sourceType as GovernanceLineageApi.SourceType] ?? source.sourceType }}：{{
                  source.sourceName || source.sourceId
                }}
              </Tag>
            </template>
          </template>
        </Table>
        <Empty v-else-if="!impactLoading" description="选择对象后分析其下游影响范围" />
      </TabPane>
      <TabPane key="sources" tab="解析记录">
        <div class="lineage-page__grid">
          <SourceGrid table-title="血缘解析记录">
            <template #sourceType="{ row }">
              {{ SOURCE_TYPE_LABELS[row.sourceType as GovernanceLineageApi.SourceType] ?? row.sourceType }}
            </template>
            <template #status="{ row }">
              <Tag :color="STATUS_LABELS[row.status as GovernanceLineageApi.ReportStatus]?.color">
                {{ STATUS_LABELS[row.status as GovernanceLineageApi.ReportStatus]?.label ?? row.status }}
              </Tag>
            </template>
            <template #issues="{ row }">
              <span v-if="!row.issues?.length" class="lineage-page__hint">无</span>
              <Tooltip v-else>
                <template #title>
                  <div v-for="(issue, index) in row.issues" :key="index">
                    {{ issue.statementIndex ? `第 ${issue.statementIndex} 条：` : '' }}{{ issue.message || issue.code }}
                  </div>
                </template>
                <span>{{ row.issues.length }} 条：{{ row.issues[0]?.message || row.issues[0]?.code }}</span>
              </Tooltip>
            </template>
          </SourceGrid>
        </div>
      </TabPane>
    </Tabs>
  </Page>
</template>

<style scoped>
.lineage-page {
  height: 100%;
}

.lineage-page__filter {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.lineage-page__hint {
  color: #86909c;
  font-size: 12px;
}

.lineage-page__explorer {
  height: calc(100vh - 260px);
  min-height: 480px;
}

.lineage-page__grid {
  height: calc(100vh - 220px);
}
</style>
