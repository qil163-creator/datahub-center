<script setup lang="ts">
import type { GovernanceMetaObjectApi as Api } from '#/api/governance/metaobject';

import { computed, onActivated, onDeactivated, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Empty,
  message,
  Modal,
  Spin,
  Table,
  TabPane,
  Tabs,
} from 'antdv-next';

import { TableAction } from '#/adapter/vxe-table';
import {
  cancelFinalizeMetaTable,
  finalizeMetaTable,
  getFinalizedMetaTable,
  getMetaColumnList,
  getMetaSnapshotList,
  getMetaTableDetail,
} from '#/api/governance/metaobject';
import { useObjectRequest } from '#/hooks/use-object-request';

import { metadataTime } from '../metadata-common/helpers';
import { metadataIcon } from '../metadata-common/icons';
import { displayComment, formatSize, formatTime } from './data';
import ColumnBizForm from './modules/column-biz-form.vue';
import LegacyDetail from './modules/detail.vue';

import '../metadata-common/metadata.css';

defineOptions({ name: 'GovernanceMetaTableDetailPage' });
const props = defineProps<{ finalized?: boolean }>();
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const id = computed(() => String(route.params.id ?? ''));
const detail = ref<Api.MetaTable>();
const fields = ref<Api.MetaColumn[]>([]);
const snapshots = ref<Api.Snapshot[]>([]);
const loading = ref(false);
const failed = ref(false);
const activeTab = ref('fields');
const selectedField = ref<Api.MetaColumn>();
const fieldPage = ref(1);
const versionPage = ref(1);
let active = true;
const request = useObjectRequest(
  () => `${props.finalized}/${id.value}`,
  () => {
    detail.value = undefined;
    fields.value = [];
    snapshots.value = [];
    selectedField.value = undefined;
  },
);
const canEdit = computed(
  () =>
    !props.finalized &&
    detail.value?.publishStatus !== 1 &&
    hasAccessByCodes(['governance:meta-table:update']),
);
const [ColumnModal, columnApi] = useVbenModal({
  connectedComponent: ColumnBizForm,
  destroyOnClose: true,
});
const [MaintenanceModal, maintenanceApi] = useVbenModal({
  connectedComponent: LegacyDetail,
  destroyOnClose: true,
});

async function load() {
  if (!id.value || !active) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    if (props.finalized) {
      const result = await getFinalizedMetaTable(id.value);
      if (current()) {
        detail.value = result.table;
        fields.value = result.columns;
        snapshots.value = [result.snapshot];
      }
    } else {
      const [table, columns, versions] = await Promise.all([
        getMetaTableDetail(id.value),
        getMetaColumnList(id.value),
        getMetaSnapshotList(id.value),
      ]);
      if (current()) {
        detail.value = table;
        fields.value = columns;
        snapshots.value = versions;
      }
    }
  } catch {
    if (current()) {
      failed.value = true;
      detail.value = undefined;
      fields.value = [];
      snapshots.value = [];
    }
  } finally {
    if (current()) loading.value = false;
  }
}
watch(
  () => [id.value, props.finalized],
  () => {
    fieldPage.value = 1;
    versionPage.value = 1;
    void load();
  },
  { immediate: true },
);
watch(
  () => route.query.tab,
  (tab) => {
    if (tab === 'base') activeTab.value = 'base';
    else if (tab === 'versions' && !props.finalized)
      activeTab.value = 'versions';
    else activeTab.value = 'fields';
  },
  { immediate: true },
);
onDeactivated(() => {
  active = false;
  request.begin();
});
onActivated(() => {
  if (!active) {
    active = true;
    void load();
  }
});
function back() {
  void router.push(
    String(
      route.meta.activePath ||
        (props.finalized
          ? '/govern/metadata/metapublished'
          : '/govern/metadata/metaobject'),
    ),
  );
}
function version(value?: string) {
  return value || '-';
}
function yes(value?: number) {
  if (value === 1) return '是';
  return value === 0 ? '否' : '-';
}
function required(value?: number) {
  if (value === 0) return '是';
  return value === 1 ? '否' : '-';
}
async function finalize(row: Api.Snapshot) {
  const table = detail.value;
  if (
    table?.id === undefined ||
    table.stateRevision === undefined ||
    row.id === undefined
  )
    return;
  await finalizeMetaTable({
    id: table.id,
    snapshotId: row.id,
    stateRevision: table.stateRevision,
  });
  message.success('已确认定版');
  await load();
}
async function revoke() {
  const table = detail.value;
  if (table?.id === undefined || table.stateRevision === undefined) return;
  await cancelFinalizeMetaTable({
    id: table.id,
    stateRevision: table.stateRevision,
  });
  message.success('已撤销定版');
  await load();
}
function openCompare(row: Api.Snapshot) {
  void router.push({
    name: 'GovernanceMetaTableCompare',
    params: { id: id.value },
    query: { snapshotId: String(row.id) },
  });
}
function editTable() {
  void router.push({
    name: 'GovernanceMetaTableEdit',
    params: { id: id.value },
  });
}
const fieldColumns = computed(() =>
  props.finalized
    ? [
        { title: '字段名称', dataIndex: 'columnName', width: 220 },
        { title: '字段注释', key: 'comment', width: 280 },
        { title: '数据类型', dataIndex: 'dataType', width: 180 },
        { title: '是否必填', key: 'required', width: 150 },
        { title: '默认值', dataIndex: 'defaultValue', width: 200 },
      ]
    : [
        { title: '编号', dataIndex: 'id', width: 80 },
        { title: '字段名称', dataIndex: 'columnName', width: 160 },
        { title: '字段注释', key: 'comment', width: 180 },
        { title: '字段类型', dataIndex: 'dataType', width: 110 },
        { title: '字段长度', dataIndex: 'dataLength', width: 95 },
        { title: '字段精度', dataIndex: 'dataPrecision', width: 95 },
        { title: '字段小数', dataIndex: 'dataScale', width: 95 },
        { title: '是否必填', key: 'required', width: 90 },
        { title: '是否主键', key: 'primary', width: 90 },
        { title: '是否外键', key: 'foreign', width: 90 },
        { title: '更新人', dataIndex: 'updaterName', width: 100 },
        { title: '更新时间', dataIndex: 'updateTime', width: 155 },
        { title: '创建人', dataIndex: 'creatorName', width: 130 },
        { title: '创建时间', dataIndex: 'createTime', width: 155 },
        { title: '操作', key: 'actions', width: 200, fixed: 'right' as const },
      ],
);
const versionColumns = [
  { title: '版本', dataIndex: 'structVersion', width: 150 },
  { title: '变更详情', dataIndex: 'changeSummary' },
  { title: '操作人', dataIndex: 'creatorName', width: 220 },
  { title: '版本生成时间', dataIndex: 'createTime', width: 220 },
  { title: '操作', key: 'actions', width: 240, fixed: 'right' as const },
];
const technicalFields = computed(() =>
  selectedField.value
    ? [
        ['字段名称', selectedField.value.columnName],
        ['字段注释', displayComment(selectedField.value)],
        ['字段类型', selectedField.value.dataType],
        ['字段长度', selectedField.value.dataLength],
        ['字段精度', selectedField.value.dataPrecision],
        ['字段小数位', selectedField.value.dataScale],
        ['业务定义', selectedField.value.businessDefinition],
        ['度量单位', selectedField.value.measuringUnit],
        ['是否必填', required(selectedField.value.nullableFlag)],
        ['默认值', selectedField.value.defaultValue],
        ['是否主键', yes(selectedField.value.pkFlag)],
        ['是否外键', yes(selectedField.value.fkFlag)],
        ['关联数据元', selectedField.value.standardElementName],
        ['创建人', selectedField.value.creatorName],
        ['创建时间', formatTime(selectedField.value.createTime)],
        ['更新人', selectedField.value.updaterName],
        ['更新时间', formatTime(selectedField.value.updateTime)],
        ['描述', selectedField.value.remark],
      ]
    : [],
);
</script>

<template>
  <Page auto-content-height content-class="metadata-page">
    <ColumnModal @success="load" /><MaintenanceModal @success="load" />
    <Spin :spinning="loading" :classes="{ root: 'metadata-detail-spin' }">
      <div class="metadata-detail">
        <Alert
          v-if="failed"
          type="error"
          message="元数据加载失败或已不可访问"
          show-icon
        >
          <template #action>
            <Button @click="load">
              <template #icon>
                <IconifyIcon :icon="metadataIcon('refresh')" />
              </template>
              重试 </Button
            ><Button @click="back">返回</Button>
          </template>
        </Alert>
        <template v-if="detail">
          <section class="metadata-panel">
            <div class="metadata-detail__title">
              <span class="metadata-detail__id">{{ detail.id }}</span>
              <h2>{{ detail.tableName }}</h2>
              <span
                class="metadata-tag"
                :class="
                  detail.publishStatus === 1
                    ? 'metadata-tag--success'
                    : 'metadata-tag--error'
                "
                >{{ detail.publishStatus === 1 ? '已发布' : '未发布' }}</span
              ><Button class="metadata-detail__back" size="small" @click="back">
                <IconifyIcon :icon="metadataIcon('back')" />返回
              </Button>
            </div>
            <Descriptions bordered size="small" :column="3">
              <DescriptionsItem label="来源系统">
                {{ detail.sourceSystemName || '-' }} </DescriptionsItem
              ><DescriptionsItem label="数据库类型">
                <span class="metadata-tag">{{
                  detail.dbType || '-'
                }}</span> </DescriptionsItem
              ><DescriptionsItem label="版本号">
                {{
                  version(
                    finalized ? detail.finalizedVersion : detail.structVersion,
                  )
                }}
              </DescriptionsItem>
              <DescriptionsItem label="所属库名">
                {{ detail.dbName || '-' }} </DescriptionsItem
              ><DescriptionsItem label="表注释" :span="2">
                {{ displayComment(detail) }}
              </DescriptionsItem>
            </Descriptions>
          </section>
          <section class="metadata-panel metadata-detail-body">
            <Tabs
              :active-key="activeTab"
              @change="(key) => (activeTab = String(key ?? 'fields'))"
            >
              <TabPane key="fields" tab="字段列表">
                <div class="metadata-detail-tools">
                  <Button size="small" aria-label="刷新字段" @click="load">
                    <IconifyIcon :icon="metadataIcon('refresh')" />
                  </Button>
                </div>
                <Table
                  class="metadata-detail-table"
                  :columns="fieldColumns"
                  :data-source="fields"
                  :loading="loading"
                  row-key="id"
                  :scroll="{ x: finalized ? 1000 : 1840 }"
                  size="small"
                  :pagination="
                    finalized
                      ? false
                      : {
                          current: fieldPage,
                          pageSize: 10,
                          showSizeChanger: false,
                          showTotal: (total: number) => `共 ${total} 条`,
                          onChange: (page: number) => (fieldPage = page),
                        }
                  "
                >
                  <template #bodyCell="{ column, record, text }">
                    <template v-if="column.key === 'comment'">
                      {{ displayComment(record) }}
                    </template>
                    <template v-else-if="column.dataIndex === 'columnName'">
                      <button
                        v-if="!finalized"
                        class="metadata-link"
                        @click="selectedField = record"
                      >
                        {{ text }}</button
                      ><span v-else>{{ text }}</span>
                    </template>
                    <template
                      v-else-if="
                        ['required', 'primary', 'foreign'].includes(
                          String(column.key ?? ''),
                        )
                      "
                    >
                      <span
                        :class="
                          finalized ? '' : 'metadata-tag metadata-tag--error'
                        "
                        >{{
                          column.key === 'required'
                            ? required(record.nullableFlag)
                            : yes(
                                column.key === 'primary'
                                  ? record.pkFlag
                                  : record.fkFlag,
                              )
                        }}</span
                      >
                    </template>
                    <template
                      v-else-if="
                        column.dataIndex === 'createTime' ||
                        column.dataIndex === 'updateTime'
                      "
                    >
                      {{ metadataTime(text, 'YYYY-MM-DD HH:mm') }}
                    </template>
                    <template v-else-if="column.key === 'actions'">
                      <Button
                        type="link"
                        size="small"
                        @click="selectedField = record"
                      >
                        <template #icon>
                          <IconifyIcon :icon="metadataIcon('detail')" />
                        </template>
                        详情 </Button
                      ><Button
                        v-if="canEdit"
                        type="link"
                        size="small"
                        @click="columnApi.setData(record).open()"
                      >
                        <template #icon>
                          <IconifyIcon :icon="metadataIcon('edit')" />
                        </template>
                        修改
                      </Button>
                    </template>
                    <template v-else>{{ text ?? '-' }}</template>
                  </template>
                </Table>
              </TabPane>
              <TabPane v-if="!finalized" key="versions" tab="版本与变更">
                <div class="metadata-detail-tools">
                  <Button size="small" aria-label="刷新版本" @click="load">
                    <IconifyIcon :icon="metadataIcon('refresh')" />
                  </Button>
                </div>
                <Table
                  class="metadata-detail-table"
                  :columns="versionColumns"
                  :data-source="snapshots"
                  row-key="id"
                  size="small"
                  :scroll="{ x: 1000 }"
                  :pagination="{
                    current: versionPage,
                    pageSize: 10,
                    showSizeChanger: false,
                    showTotal: (total: number) => `共 ${total} 条`,
                    onChange: (page: number) => (versionPage = page),
                  }"
                >
                  <template #bodyCell="{ column, record, text }">
                    <template v-if="column.dataIndex === 'createTime'">
                      {{ metadataTime(text, 'YYYY-MM-DD HH:mm') }}
                    </template>
                    <template v-else-if="column.key === 'actions'">
                      <TableAction
                        :actions="[
                          {
                            label: '版本对比',
                            type: 'link',
                            icon: metadataIcon('detail'),
                            onClick: () => openCompare(record),
                          },
                          {
                            label:
                              String(detail.finalizedSnapshotId) ===
                              String(record.id)
                                ? '撤销定版'
                                : '确认定版',
                            type: 'link',
                            icon: metadataIcon(
                              String(detail.finalizedSnapshotId) ===
                                String(record.id)
                                ? 'cancel-finalize'
                                : 'finalize-confirm',
                            ),
                            auth: ['governance:meta-table:publish'],
                            popConfirm: {
                              title:
                                String(detail.finalizedSnapshotId) ===
                                String(record.id)
                                  ? '确认撤销定版该版本吗？'
                                  : `确认定版 ${record.structVersion}？`,
                              confirm: () =>
                                String(detail?.finalizedSnapshotId) ===
                                String(record.id)
                                  ? revoke()
                                  : finalize(record),
                            },
                          },
                        ]"
                      /> </template
                    ><template v-else>{{ text || '-' }}</template>
                  </template>
                </Table>
              </TabPane>
              <TabPane key="base" tab="基本信息">
                <h3 class="metadata-section">基础信息</h3>
                <Descriptions bordered size="small" :column="3">
                  <DescriptionsItem label="所属分层">
                    {{ detail.layer || '-' }} </DescriptionsItem
                  ><DescriptionsItem label="所属系统" :span="2">
                    {{ detail.datasourceName || '-' }}
                  </DescriptionsItem>
                  <DescriptionsItem label="描述" :span="3">
                    {{ detail.businessDefinition || displayComment(detail) }}
                  </DescriptionsItem>
                  <DescriptionsItem label="更新人">
                    {{ detail.updaterName || '-' }} </DescriptionsItem
                  ><DescriptionsItem label="更新时间">
                    {{ formatTime(detail.updateTime) }} </DescriptionsItem
                  ><DescriptionsItem label="创建人">
                    {{ detail.creatorName || '-' }}
                  </DescriptionsItem>
                  <DescriptionsItem label="创建时间" :span="3">
                    {{ formatTime(detail.createTime) }} </DescriptionsItem
                  ><DescriptionsItem label="备注" :span="3">
                    {{ detail.remark || '-' }}
                  </DescriptionsItem>
                </Descriptions>
                <h3 class="metadata-section">技术信息</h3>
                <Descriptions bordered size="small" :column="3">
                  <DescriptionsItem label="数据源名称">
                    {{ detail.dbName || '-' }} </DescriptionsItem
                  ><DescriptionsItem label="IP">
                    {{ detail.host || '-' }} </DescriptionsItem
                  ><DescriptionsItem label="端口号">
                    {{ detail.port || '-' }}
                  </DescriptionsItem>
                  <DescriptionsItem label="账号">
                    {{ detail.username || '-' }} </DescriptionsItem
                  ><DescriptionsItem label="存储类型">
                    {{ detail.storageType || '-' }} </DescriptionsItem
                  ><DescriptionsItem label="存储大小">
                    {{ formatSize(detail.storageSize) }}
                  </DescriptionsItem>
                  <DescriptionsItem label="技术责任人">
                    {{ detail.techLeaderName || '-' }} </DescriptionsItem
                  ><DescriptionsItem label="技术责任人电话" :span="2">
                    {{ detail.techLeaderPhone || '-' }}
                  </DescriptionsItem>
                </Descriptions>
                <h3 class="metadata-section">业务信息</h3>
                <Descriptions bordered size="small" :column="3">
                  <DescriptionsItem label="是否主表">
                    {{ yes(detail.masterFlag) }} </DescriptionsItem
                  ><DescriptionsItem label="是否临时表">
                    {{ yes(detail.temporaryFlag) }} </DescriptionsItem
                  ><DescriptionsItem label="业务责任人">
                    {{ detail.businessLeaderName || '-' }}
                  </DescriptionsItem>
                  <DescriptionsItem label="业务责任人电话" :span="3">
                    {{ detail.businessLeaderPhone || '-' }}
                  </DescriptionsItem>
                </Descriptions>
                <div v-if="!finalized" class="metadata-detail-tools">
                  <Button v-if="canEdit" @click="editTable">
                    <template #icon>
                      <IconifyIcon :icon="metadataIcon('edit')" />
                    </template>
                    修改业务信息 </Button
                  ><Button @click="maintenanceApi.setData(detail).open()">
                    <template #icon>
                      <IconifyIcon :icon="metadataIcon('compare')" />
                    </template>
                    标签与变更记录
                  </Button>
                </div>
              </TabPane>
            </Tabs>
          </section>
        </template>
        <Empty v-else-if="!loading && !failed" description="暂无元数据" />
      </div>
    </Spin>
    <Modal
      :open="!!selectedField"
      title="字段元数据详情"
      :width="1200"
      :footer="null"
      @cancel="selectedField = undefined"
    >
      <div class="metadata-field-detail">
        <div v-for="(item, index) in technicalFields" :key="index">
          <label>{{ item[0] }}</label
          ><span>{{ item[1] ?? '-' }}</span>
        </div>
      </div>
      <div class="metadata-detail-tools">
        <Button @click="selectedField = undefined">关闭</Button>
      </div>
    </Modal>
  </Page>
</template>
<style scoped>
.metadata-detail-spin {
  flex: 1;
  min-height: 0;
}

.metadata-detail-spin :deep(.ant-spin-container) {
  height: 100%;
}

.metadata-detail-body {
  flex: 1;
  min-height: 480px;
}

.metadata-detail-tools {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin: 10px 0 16px;
}

.metadata-field-detail {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px 30px;
  padding: 20px;
}

.metadata-field-detail > div {
  display: grid;
  grid-template-columns: 100px 1fr;
  gap: 12px;
  align-items: center;
}

.metadata-field-detail label {
  color: hsl(var(--muted-foreground));
  text-align: right;
}

.metadata-field-detail span {
  min-height: 32px;
  padding: 5px 10px;
  background: hsl(var(--muted));
  border: 1px solid hsl(var(--border));
}

.metadata-compare-filter {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  margin-bottom: 20px;
}

@media (max-width: 900px) {
  .metadata-field-detail {
    grid-template-columns: 1fr;
  }
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
