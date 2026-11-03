<script setup lang="ts">
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';

import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  message,
  Spin,
  Table,
  TabPane,
  Tabs,
  Tooltip,
} from 'antdv-next';

import {
  getMasterDistribute,
  getMasterDistributeLogPage,
  getPushModel,
  updateMasterDistribute,
} from '#/api/governance/masterdistribute';

import { useMasterScope } from '../../mastercommon/use-master-scope';
import {
  isRunBlocked,
  optionLabel,
  RUN_STATUS_OPTIONS,
  RUN_TYPE_OPTIONS,
} from '../data';
import {
  pushModeLabel,
  pushScopeLabel,
  TRANSFORM_OPTIONS,
  validateMapping,
} from '../push-config';
import Log from './log.vue';
import MappingEditor from './mapping-editor.vue';

const props = defineProps<{ id: number | string }>();
const emit = defineEmits<{ back: []; changed: [] }>();
const detail = ref<GovernanceMasterDistributeApi.Distribute>();
const loading = ref(false);
const failed = ref(false);
const tab = ref('rest');
const logs = ref<GovernanceMasterDistributeApi.DistributeLog[]>([]);
const logLoading = ref(false);
const logFailed = ref(false);
const logPage = ref(1);
const logSize = ref(10);
const logTotal = ref(0);
const mappingColumns = ref<GovernanceMasterDataApi.Column[]>([]);
const mappingRows = ref<GovernanceMasterDistributeApi.FieldMapping[]>([]);
const mappingSource = ref<GovernanceMasterDistributeApi.Distribute>();
const mappingError = ref('');
const scope = useMasterScope(() => {
  detail.value = undefined;
  logs.value = [];
  void mappingApi.close();
  void logApi.close?.();
  void load();
});
const { hasAccessByCodes } = useAccess();
const mappingBlockedReason = computed(() => {
  if (
    detail.value?.status === 1 &&
    !hasAccessByCodes(['governance:master-distribute:release'])
  )
    return '修改已启用的推送配置需要启停权限';
  if (detail.value && isRunBlocked(detail.value))
    return '请等待当前执行结束或完成结果核查后修改映射';
  return undefined;
});
const canMap = computed(() => !!detail.value && !mappingBlockedReason.value);
const api = computed(() => detail.value?.config?.api);
const [LogModal, logApi] = useVbenModal({
  connectedComponent: Log,
  destroyOnClose: true,
});
function time(value?: number) {
  return value === null || value === undefined ? '-' : formatDateTime(value);
}
async function load() {
  const current = scope.begin('detail');
  loading.value = true;
  failed.value = false;
  try {
    const result = await getMasterDistribute(props.id);
    if (current()) detail.value = result;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function loadLogs() {
  const current = scope.begin('logs');
  logLoading.value = true;
  logFailed.value = false;
  try {
    const result = await getMasterDistributeLogPage({
      distributeId: props.id,
      pageNo: logPage.value,
      pageSize: logSize.value,
    });
    if (current()) {
      logs.value = result.list;
      logTotal.value = result.total;
    }
  } catch {
    if (current()) {
      logs.value = [];
      logFailed.value = true;
    }
  } finally {
    if (current()) logLoading.value = false;
  }
}
function manageLogs() {
  logApi.setData(detail.value).open();
}
async function changed() {
  emit('changed');
  await load();
  if (tab.value === 'logs') await loadLogs();
}
const [MappingModal, mappingApi] = useVbenModal({
  async onOpenChange(opened) {
    const current = scope.begin('mapping');
    scope.begin('mapping-save');
    mappingSource.value = undefined;
    mappingRows.value = [];
    mappingColumns.value = [];
    mappingError.value = '';
    mappingApi.unlock();
    if (!opened) return;
    mappingApi.lock();
    try {
      const fresh = await getMasterDistribute(props.id);
      if (!current()) return;
      if (
        fresh.status === 1 &&
        !hasAccessByCodes(['governance:master-distribute:release'])
      ) {
        mappingError.value = '修改已启用的推送配置需要启停权限';
        return;
      }
      if (isRunBlocked(fresh)) {
        mappingError.value = '请等待当前执行结束或完成结果核查后修改映射';
        return;
      }
      const model = await getPushModel(fresh.masterModelId!);
      if (!current()) return;
      mappingSource.value = fresh;
      mappingRows.value = (fresh.config?.api?.mappings ?? []).map((row) => ({
        ...row,
      }));
      mappingColumns.value = model.columns ?? [];
    } catch {
      if (current()) mappingError.value = '字段映射加载失败，请关闭后重试';
    } finally {
      if (current()) mappingApi.unlock();
    }
  },
  async onConfirm() {
    if (!mappingSource.value?.config?.api) return;
    mappingError.value = validateMapping(mappingRows.value) ?? '';
    if (mappingError.value) return;
    const current = scope.begin('mapping-save');
    mappingApi.lock();
    try {
      const source = mappingSource.value;
      await updateMasterDistribute({
        ...source,
        config: {
          ...source.config,
          api: {
            ...source.config?.api,
            mappings: mappingRows.value.map((row) => ({ ...row })),
          },
        },
      });
      if (!current()) return;
      message.success('字段映射已保存');
      await mappingApi.close();
      await changed();
    } finally {
      if (current()) mappingApi.unlock();
    }
  },
});
watch(
  () => props.id,
  () => {
    scope.invalidate();
    void mappingApi.close();
    void logApi.close?.();
    detail.value = undefined;
    logs.value = [];
    tab.value = 'rest';
    logPage.value = 1;
    void load();
  },
  { immediate: true },
);
watch(tab, (value) => {
  if (value === 'logs') void loadLogs();
});
const logColumns = [
  { title: '编号', dataIndex: 'id', key: 'id', width: 90 },
  { title: '触发类型', dataIndex: 'runType', key: 'runType', width: 130 },
  { title: '推送范围', dataIndex: 'pushScope', key: 'pushScope', width: 130 },
  { title: '推送模式', dataIndex: 'pushMode', key: 'pushMode', width: 130 },
  { title: '推送时间', dataIndex: 'startTime', key: 'startTime', width: 180 },
  { title: '推送总数', dataIndex: 'totalCount', key: 'totalCount', width: 110 },
  {
    title: '成功数',
    dataIndex: 'successCount',
    key: 'successCount',
    width: 95,
  },
  {
    title: '失败数',
    dataIndex: 'failureCount',
    key: 'failureCount',
    width: 95,
  },
  { title: '推送结果', dataIndex: 'runStatus', key: 'runStatus', width: 170 },
  {
    title: '失败原因',
    dataIndex: 'errorInfo',
    key: 'errorInfo',
    ellipsis: true,
  },
];
</script>

<template>
  <LogModal @success="changed" />
  <Spin v-if="loading && !detail" class="m-auto" />
  <Alert v-else-if="failed" type="error" message="主数据推送详情加载失败">
    <template #action>
      <Button @click="load">
        <template #icon>
          <IconifyIcon icon="lucide:refresh-cw" />
        </template>
        重试 </Button
      ><Button @click="emit('back')">返回</Button>
    </template>
  </Alert>
  <div v-else-if="detail" class="metadata-detail">
    <section class="metadata-panel">
      <div class="metadata-detail__title">
        <span class="metadata-detail__id">{{ detail.id }}</span>
        <h2>{{ detail.name }} - {{ detail.masterModelName }}</h2>
        <span class="metadata-tag">{{
          detail.status === 1 ? '启用' : '禁用'
        }}</span
        ><Button
          size="small"
          class="metadata-detail__back"
          @click="emit('back')"
        >
          <IconifyIcon icon="lucide:undo-2" />返回
        </Button>
      </div>
      <Descriptions bordered size="small" :column="{ xs: 1, sm: 2, xl: 3 }">
        <DescriptionsItem label="实体模型编码">
          {{ detail.masterModelTableName || '-' }} </DescriptionsItem
        ><DescriptionsItem label="推送范围">
          <span class="metadata-tag">{{
            pushScopeLabel(api?.pushScope)
          }}</span> </DescriptionsItem
        ><DescriptionsItem label="推送模式">
          <span class="metadata-tag metadata-tag--success">{{
            pushModeLabel(api?.pushMode)
          }}</span>
        </DescriptionsItem>
        <DescriptionsItem label="描述" :span="3">
          {{ detail.description || '-' }}
        </DescriptionsItem>
        <DescriptionsItem label="创建人">
          {{ detail.creatorName || '-' }} </DescriptionsItem
        ><DescriptionsItem label="创建时间">
          {{ time(detail.createTime) }} </DescriptionsItem
        ><DescriptionsItem label="最近推送时间">
          {{ time(detail.lastPushTime) }}
        </DescriptionsItem>
      </Descriptions>
    </section>
    <section class="metadata-panel">
      <Tabs v-model:active-key="tab">
        <TabPane
          key="rest"
          :tab="detail.distributeType === 2 ? 'REST 接收配置' : '推送配置'"
        >
          <Descriptions
            v-if="api"
            bordered
            size="small"
            :column="{ xs: 1, md: 2 }"
          >
            <DescriptionsItem label="下游接收地址" :span="2">
              {{ api.url }}
            </DescriptionsItem>
            <DescriptionsItem label="请求方式">
              {{ api.method || 'POST' }} </DescriptionsItem
            ><DescriptionsItem label="认证方式">
              {{ api.authType === 'TOKEN' ? 'Token' : '无认证' }}
            </DescriptionsItem>
            <DescriptionsItem label="超时时间">
              {{ api.timeoutSeconds ?? 30 }} 秒 </DescriptionsItem
            ><DescriptionsItem label="请求内容">JSON</DescriptionsItem>
          </Descriptions>
          <Descriptions v-else bordered size="small" :column="{ xs: 1, md: 2 }">
            <DescriptionsItem label="推送类型">
              {{
                detail.distributeType === 1 ? '数据库' : '消息队列'
              }} </DescriptionsItem
            ><DescriptionsItem label="目标">
              {{
                detail.config?.db?.targetTableName ||
                detail.config?.mq?.kafka?.topic ||
                detail.config?.mq?.rabbit?.exchange ||
                '-'
              }}
            </DescriptionsItem>
            <DescriptionsItem label="调度类型">
              {{
                detail.taskType === 2 ? '周期性' : '一次性'
              }} </DescriptionsItem
            ><DescriptionsItem label="Cron">
              {{ detail.cron || '-' }}
            </DescriptionsItem>
          </Descriptions>
        </TabPane>
        <TabPane key="mapping" tab="字段映射">
          <div class="metadata-toolbar">
            <Tooltip :title="mappingBlockedReason">
              <span
                ><Button
                  v-access:code="'governance:master-distribute:update'"
                  type="primary"
                  size="small"
                  :disabled="!canMap || !api"
                  @click="mappingApi.open()"
                  >＋ 字段映射</Button
                ></span
              >
            </Tooltip>
          </div>
          <div class="qdata-master-mapping">
            <Table
              :data-source="api?.mappings ?? []"
              :pagination="false"
              :row-key="(_row, index) => index ?? ''"
              :columns="[
                { title: '编号', key: 'index', width: 80 },
                { title: '是否推送', key: 'enabled', width: 110 },
                { title: '模型字段', dataIndex: 'sourceField' },
                { title: '下游字段', dataIndex: 'targetField' },
                { title: '转换规则', key: 'transform' },
                { title: '默认值', dataIndex: 'defaultValue' },
              ]"
            >
              <template #bodyCell="{ column, record, text, index }">
                <template v-if="column.key === 'index'">
                  {{ index + 1 }}
                </template>
                <template v-else-if="column.key === 'enabled'">
                  {{ record.enabled ? '是' : '否' }}
                </template>
                <template v-else-if="column.key === 'transform'">
                  <span class="metadata-tag">{{
                    TRANSFORM_OPTIONS.find(
                      (option) => option.value === record.transform,
                    )?.label || record.transform
                  }}</span>
                </template>
                <template v-else>{{ text || '-' }}</template>
              </template>
              <template #emptyText>
                <div class="qdata-master-empty">
                  <img
                    src="../../mastercommon/reference-assets/empty-records.png"
                    alt=""
                    aria-hidden="true"
                  />
                  <span>暂无记录</span>
                </div>
              </template>
            </Table>
          </div>
        </TabPane>
        <TabPane key="logs" tab="推送日志">
          <div
            v-if="logs.some((row) => [1, 2, 5].includes(row.runStatus ?? 0))"
            class="mb-3 flex justify-end"
          >
            <Button size="small" @click="manageLogs">
              <template #icon>
                <IconifyIcon icon="lucide:shield-check" /> </template
              >执行管理与核查
            </Button>
          </div>
          <Alert v-if="logFailed" type="error" message="推送日志加载失败">
            <template #action>
              <Button @click="loadLogs">
                <template #icon>
                  <IconifyIcon icon="lucide:refresh-cw" />
                </template>
                重试
              </Button>
            </template>
          </Alert>
          <Table
            class="qdata-master-mapping"
            :columns="logColumns"
            :data-source="logs"
            :row-key="(row) => String(row.id)"
            :loading="logLoading"
            :scroll="{ x: 1400 }"
            :pagination="{
              current: logPage,
              pageSize: logSize,
              total: logTotal,
              showSizeChanger: true,
              showQuickJumper: true,
              showTotal: (total) => `共 ${total} 条`,
            }"
            @change="
              (page) => {
                logPage = page.current ?? 1;
                logSize = page.pageSize ?? 10;
                loadLogs();
              }
            "
          >
            <template #bodyCell="{ column, record, text }">
              <template v-if="column.key === 'runType'">
                {{ optionLabel(RUN_TYPE_OPTIONS, record.runType) }} </template
              ><template v-else-if="column.key === 'pushScope'">
                {{ pushScopeLabel(record.pushScope) }} </template
              ><template v-else-if="column.key === 'pushMode'">
                {{ pushModeLabel(record.pushMode) }} </template
              ><template v-else-if="column.key === 'startTime'">
                {{ time(record.startTime) }} </template
              ><template v-else-if="column.key === 'runStatus'">
                {{
                  optionLabel(RUN_STATUS_OPTIONS, record.runStatus)
                }} </template
              ><template v-else>{{ text ?? '-' }}</template>
            </template>
            <template #emptyText>
              <div class="qdata-master-empty">
                <img
                  src="../../mastercommon/reference-assets/empty-records.png"
                  alt=""
                  aria-hidden="true"
                /><span>暂无记录</span>
              </div>
            </template>
          </Table>
        </TabPane>
        <TabPane key="info" tab="详细信息">
          <Descriptions bordered size="small" :column="{ xs: 1, md: 2 }">
            <DescriptionsItem label="推送总数">
              {{ detail.totalCount ?? 0 }} </DescriptionsItem
            ><DescriptionsItem label="成功 / 失败">
              {{ detail.successCount ?? 0 }} /
              {{ detail.failureCount ?? 0 }} </DescriptionsItem
            ><DescriptionsItem
              v-if="detail.unknownCount && String(detail.unknownCount) !== '0'"
              label="结果未知"
            >
              {{ detail.unknownCount }} </DescriptionsItem
            ><DescriptionsItem label="创建人">
              {{ detail.creatorName || '-' }} </DescriptionsItem
            ><DescriptionsItem label="创建时间">
              {{ time(detail.createTime) }} </DescriptionsItem
            ><DescriptionsItem label="更新人">
              {{ detail.updaterName || '-' }} </DescriptionsItem
            ><DescriptionsItem label="更新时间">
              {{ time(detail.updateTime) }}
            </DescriptionsItem>
          </Descriptions>
        </TabPane>
      </Tabs>
    </section>
    <MappingModal
      title="字段映射配置"
      class="metadata-modal qdata-master-modal"
      header-class="metadata-modal__header"
      content-class="metadata-modal__body"
      footer-class="metadata-modal__footer"
    >
      <Alert
        v-if="mappingError"
        type="error"
        :message="mappingError"
        class="mb-4"
      />
      <div class="metadata-section">基础配置</div>
      <div class="metadata-form-grid">
        <div class="metadata-form-item">
          <label>下游系统</label
          ><span class="mapping-readonly">{{ detail.name }}</span>
        </div>
        <div class="metadata-form-item">
          <label>主数据对象</label
          ><span class="mapping-readonly">{{ detail.masterModelName }}</span>
        </div>
        <div class="metadata-form-item">
          <label>推送范围</label
          ><span class="mapping-readonly">{{
            pushScopeLabel(api?.pushScope)
          }}</span>
        </div>
        <div class="metadata-form-item">
          <label>推送模式</label
          ><span class="mapping-readonly">{{
            pushModeLabel(api?.pushMode)
          }}</span>
        </div>
      </div>
      <div class="metadata-section">字段映射</div>
      <MappingEditor
        v-model="mappingRows"
        :columns="mappingColumns"
        :disabled="!mappingSource"
      />
    </MappingModal>
  </div>
</template>

<style scoped>
.mapping-readonly {
  display: block;
  padding: 5px 10px;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted));
  border: 1px solid hsl(var(--border));
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
