<script setup lang="ts">
import type { GovernanceMetaCollectInstanceApi } from '#/api/governance/metacollectinstance';
import type { GovernanceMetaCollectTaskApi as Api } from '#/api/governance/metacollecttask';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  TabPane,
  Tabs,
} from 'antdv-next';

import { getMetaCollectInstancePage } from '#/api/governance/metacollectinstance';
import { getMetaCollectTask } from '#/api/governance/metacollecttask';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  INSTANCE_STATUS_OPTIONS,
  optionLabel as instanceLabel,
} from '../metacollectinstance/data';
import Log from '../metacollectinstance/modules/log.vue';
import { metadataTime } from '../metadata-common/helpers';
import MetadataIcon from '../metadata-common/metadata-icon.vue';
import { useMetadataGrid } from '../metadata-common/use-metadata-grid';
import {
  EXECUTE_STRATEGY_OPTIONS,
  optionLabel,
  SCOPE_TYPE_OPTIONS,
} from './data';

import '../metadata-common/metadata.css';

defineOptions({ name: 'GovernanceMetaCollectTaskDetail' });
const route = useRoute();
const router = useRouter();
const id = computed(() =>
  route.name === 'GovernanceMetaCollectTaskDetail'
    ? String(route.params.id ?? '')
    : '',
);
const detail = ref<Api.CollectTask>();
const tab = ref('instances');
const failed = ref(false);
const { hasAccessByCodes } = useAccess();
const request = useObjectRequest(
  () => id.value,
  () => {
    detail.value = undefined;
  },
);
const [LogModal, logApi] = useVbenModal({
  connectedComponent: Log,
  destroyOnClose: true,
});
const {
  Grid,
  gridApi,
  load: loadInstances,
  error: instanceError,
} = useMetadataGrid<GovernanceMetaCollectInstanceApi.CollectInstance>(
  [
    { field: 'id', title: '编号', width: 90 },
    { title: '采集范围', width: 140, slots: { default: 'scope' } },
    { title: '采集数量', width: 150, slots: { default: 'count' } },
    { title: '变更数量', minWidth: 200, slots: { default: 'changes' } },
    { title: '运行状态', width: 100, slots: { default: 'status' } },
    { field: 'failDetail', title: '失败原因', minWidth: 200 },
    {
      field: 'durationMs',
      title: '采集耗时(s)',
      width: 130,
      formatter: ({ cellValue }) =>
        cellValue === null || cellValue === undefined
          ? '-'
          : Math.round(cellValue / 1000),
    },
    { title: '采集起止时间', minWidth: 310, slots: { default: 'time' } },
    {
      title: '操作',
      width: 140,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ],
  (page) =>
    id.value && hasAccessByCodes(['governance:meta-collect-instance:query'])
      ? getMetaCollectInstancePage({ ...page, taskId: id.value })
      : Promise.resolve({ list: [], total: 0 }),
  undefined,
  undefined,
  'content',
);
gridApi.setGridOptions({ headerCellConfig: { height: 34 }, height: 'auto' });
async function load() {
  if (!id.value) return;
  const current = request.begin();
  failed.value = false;
  try {
    const result = await getMetaCollectTask(id.value);
    if (current()) detail.value = result;
  } catch {
    if (current()) failed.value = true;
  }
}
const scopeLabel = computed(() => {
  if (detail.value?.collectType === 2) return detail.value.filePath || '/';
  if (detail.value?.scopeType !== 2) return '整个数据源';
  try {
    const rows = JSON.parse(detail.value.scopeConfig || '[]') as Api.Scope[];
    return rows
      .map(
        (item) =>
          item.db +
          (item.tables.length > 0
            ? '：' + item.tables.join('、')
            : '（全部表）'),
      )
      .join('；');
  } catch {
    return '采集范围格式异常';
  }
});
watch(
  id,
  () => {
    void load();
    void loadInstances(true);
  },
  { immediate: true },
);
</script>
<template>
  <Page auto-content-height content-class="metadata-page">
    <LogModal />
    <Alert v-if="failed" type="error" message="任务详情读取失败">
      <template #action>
        <Button @click="load">
          <template #icon>
            <MetadataIcon name="refresh" />
          </template>
          重试
        </Button>
      </template>
    </Alert>
    <div class="metadata-detail">
      <section class="metadata-panel">
        <div class="metadata-detail__title">
          <span class="metadata-detail__id">{{ detail?.id }}</span>
          <h2>{{ detail?.name || '采集任务详情' }}</h2>
          <span
            class="metadata-tag"
            :class="
              detail?.enabled === 1
                ? 'metadata-tag--success'
                : 'metadata-tag--error'
            "
            >{{ detail?.enabled === 1 ? '上线' : '下线' }}</span><DetailBackButton
            class="ml-auto"
            @click="
              router.push(
                String(
                  route.meta.activePath || '/govern/metadata/metacollecttask',
                ),
              )
            "
            />
        </div>
        <Descriptions bordered size="small" :column="3">
          <DescriptionsItem label="来源系统">
            {{ detail?.sourceSystemName || '-' }}
</DescriptionsItem><DescriptionsItem label="责任人">
            {{ detail?.ownerName || '-' }}
</DescriptionsItem><DescriptionsItem label="责任人电话">
            {{ detail?.ownerPhone || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="描述" :span="3">
            {{ detail?.description || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="最近执行时间">
            {{ metadataTime(detail?.lastRunTime) }}
</DescriptionsItem><DescriptionsItem label="下次执行时间">
            {{ metadataTime(detail?.nextRunTime) }}
          </DescriptionsItem>
        </Descriptions>
      </section>
      <section class="metadata-panel flex min-h-0 flex-1 flex-col">
        <Tabs v-model:active-key="tab" class="metadata-task-tabs">
          <TabPane key="instances" tab="采集实例">
            <Alert v-if="instanceError" type="error" message="实例读取失败" />
            <div class="metadata-task-instances">
              <Grid class="metadata-grid" table-title="采集实例列表">
                <template #toolbar-tools>
                  <Button
                    size="small"
                    aria-label="刷新"
                    @click="loadInstances()"
                  >
                    <template #icon>
                      <MetadataIcon name="refresh" />
                    </template>
                  </Button>
                </template>
                <template #scope>
                  {{
                    detail?.collectType === 2
                      ? '文件目录'
                      : optionLabel(SCOPE_TYPE_OPTIONS, detail?.scopeType)
                  }}
                </template>
                <template #count="{ row }">
                  {{
                    row.collectType === 2
                      ? (row.fileCount ?? 0)
                      : (row.tableCount ?? 0)
                  }}<template v-if="row.status === 2">
                    （成功
                    {{
                      row.collectType === 2
                        ? (row.fileCount ?? 0)
                        : (row.tableCount ?? 0)
                    }}，失败 0）
                  </template>
                </template>
                <template #changes="{ row }">
                  新增 {{ row.tableAdd ?? 0 }}，删除
                  {{ row.tableDelete ?? 0 }}，变更
                  {{ row.tableUpdate ?? 0 }}
                </template>
                <template #status="{ row }">
                  <span
                    class="metadata-tag"
                    :class="{
                      'metadata-tag--success': row.status === 2,
                      'metadata-tag--error': row.status === 3,
                    }"
                    >{{
                      instanceLabel(INSTANCE_STATUS_OPTIONS, row.status)
                    }}</span>
                </template>
                <template #time="{ row }">
                  {{ metadataTime(row.startTime) }} ~
                  {{ metadataTime(row.endTime) }}
                </template>
                <template #actions="{ row }">
                  <Button
                    type="link"
                    size="small"
                    @click="logApi.setData(row).open()"
                  >
                    <template #icon><MetadataIcon name="instances" /></template>
                    查看日志
                  </Button>
                </template>
              </Grid>
            </div>
          </TabPane>
          <TabPane key="base" tab="基本信息">
            <Descriptions bordered size="small" :column="2">
              <DescriptionsItem label="数据连接名称">
                {{ detail?.datasourceName || '-' }}
</DescriptionsItem><DescriptionsItem label="数据连接类型">
                <span class="metadata-tag metadata-tag--source">{{
                  detail?.dbType || '-'
                }}</span>
              </DescriptionsItem>
              <DescriptionsItem label="IP地址">
                {{ detail?.host || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="端口号">
                {{ detail?.port || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="账号">
                {{ detail?.username || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="调度周期">
                {{ detail?.cron || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="采集范围" :span="2">
                {{ scopeLabel }}
              </DescriptionsItem>
              <DescriptionsItem label="执行策略">
                {{
                  optionLabel(EXECUTE_STRATEGY_OPTIONS, detail?.executeStrategy)
                }}
</DescriptionsItem><DescriptionsItem label="更新人">
                {{ detail?.updaterName || '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="更新时间">
                {{ metadataTime(detail?.updateTime) }}
              </DescriptionsItem>
              <DescriptionsItem label="创建人">
                {{ detail?.creatorName || '-' }}
</DescriptionsItem><DescriptionsItem label="创建时间">
                {{ metadataTime(detail?.createTime) }}
              </DescriptionsItem>
            </Descriptions>
          </TabPane>
        </Tabs>
      </section>
    </div>
  </Page>
</template>

<style scoped>
.metadata-task-tabs {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.metadata-task-tabs :deep(.ant-tabs-body-holder) {
  flex: 1;
  min-height: 0;
}

.metadata-task-tabs :deep(.ant-tabs-body),
.metadata-task-tabs :deep(.ant-tabs-content) {
  height: 100%;
}

.metadata-task-tabs :deep(.ant-tabs-content-active) {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.metadata-task-instances {
  display: flex;
  flex: 1;
  min-height: 0;
}
</style>
