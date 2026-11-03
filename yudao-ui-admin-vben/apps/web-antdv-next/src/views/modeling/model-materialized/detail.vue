<script setup lang="ts">
import type { ModelingModelApi } from '#/api/modeling/model';
import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictLabel } from '@vben/hooks';
import { useAccessStore } from '@vben/stores';
import { formatDate } from '@vben/utils';

import { Alert, Modal, Spin } from 'antdv-next';

import {
  getModelMaterializedByModel,
  getModelMaterializedLogPage,
} from '#/api/modeling/model-materialized';

import sectionIcon from '../logictable/assets/data-section-default-icon-small.svg';
import { LOGIC_TABLE_PAGES } from '../logictable/config';
import { QdIcon } from '../logictable/icons';
import { useModelingRequest } from '../modelcommon/use-modeling-request';
import backIcon from './assets/icon-fhs.svg';
import materializeIcon from './assets/icon-wh.svg';
import FieldDetailModal from './modules/field-detail-modal.vue';
import PublishModal from './modules/publish-modal.vue';
import RecoveryModal from './modules/recovery-modal.vue';
import ReferencePagination from './modules/reference-pagination.vue';
import {
  MATERIALIZED_STATUS_OPTIONS,
  materializedStatus,
  recoveryAction,
} from './state';

import '../logictable/logic-table.css';
import './detail.css';

defineOptions({ name: 'ModelingMaterializedDetail' });

type FieldRow = ModelingModelApi.ModelField & { key: string };
const backMask = { maskImage: `url("${backIcon}")` };
const materializeMask = { maskImage: `url("${materializeIcon}")` };

const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const modelId = computed(() => {
  const value = route.params.modelId;
  return typeof value === 'string' && /^[1-9][0-9]*$/.test(value)
    ? value
    : undefined;
});
const detail = ref<ModelMaterializedApi.Materialized>();
const loading = ref(false);
const loadError = ref(false);
const activeTab = ref('fields');
const fieldsPage = ref(1);
const fieldsPageSize = ref(10);
const fieldSortDescending = ref(false);
const fieldSortApplied = ref(false);
const logs = ref<ModelMaterializedApi.LogItem[]>([]);
const logsPage = ref(1);
const logsPageSize = ref(10);
const logsTotal = ref(0);
const logsLoading = ref(false);
const logsError = ref(false);
const hiddenFieldColumns = ref<string[]>([]);
const hiddenLogColumns = ref<string[]>([]);
const fieldModal = ref<InstanceType<typeof FieldDetailModal>>();
const publishModal = ref<InstanceType<typeof PublishModal>>();
const recoveryModal = ref<InstanceType<typeof RecoveryModal>>();
const executionLog = ref<ModelMaterializedApi.LogItem>();
const executionOpen = ref(false);

const fieldColumns = [
  { key: 'fieldUid', title: '编号', width: '5%' },
  { key: 'standardName', title: '关联标准', width: '12%' },
  { key: 'cnName', title: '中文名称', width: '12%' },
  { key: 'fieldName', title: '英文名称', width: '12%' },
  { key: 'comment', title: '描述', width: '14%' },
  { key: 'dataType', title: '数据类型', width: '7%' },
  { key: 'dataLength', title: '属性长度', width: '6%' },
  { key: 'pkFlag', title: '是否主键', width: '6%' },
  { key: 'creator', title: '创建人', width: '8%' },
  { key: 'createTime', title: '创建时间', width: '10%' },
];
const logColumns = [
  { key: 'id', title: '编号', width: '60px' },
  { key: 'modelName', title: '模型编码', width: '240px' },
  { key: 'modelAlias', title: '模型名称', width: '240px' },
  { key: 'description', title: '描述', width: '250px' },
  { key: 'datasourceType', title: '数据连接类型', width: '160px' },
  { key: 'datasourceName', title: '数据连接名称', width: '265px' },
  { key: 'publishMode', title: '发布模式', width: '120px' },
  { key: 'message', title: '执行日志信息', width: '220px' },
  { key: 'status', title: '状态', width: '80px' },
  { key: 'creatorName', title: '创建人', width: '120px' },
  { key: 'createTime', title: '创建时间', width: '160px' },
];
const visibleFieldColumns = computed(() =>
  fieldColumns.filter(
    (column) => !hiddenFieldColumns.value.includes(column.key),
  ),
);
const visibleLogColumns = computed(() =>
  logColumns.filter((column) => !hiddenLogColumns.value.includes(column.key)),
);
const tableType = computed(() =>
  Object.values(LOGIC_TABLE_PAGES).find(
    (item) => item.tableType === detail.value?.tableType,
  ),
);
const status = computed(() => materializedStatus(detail.value));
const statusLabel = computed(() =>
  status.value === 'SUCCESS'
    ? '发布成功'
    : MATERIALIZED_STATUS_OPTIONS.find((item) => item.value === status.value)
        ?.label || status.value,
);
const belonging = computed(
  () =>
    [detail.value?.dataDomainName, detail.value?.subjectDomainName]
      .filter(Boolean)
      .join(' / ') ||
    detail.value?.layerName ||
    '-',
);
const fields = computed<FieldRow[]>(() =>
  (detail.value?.fields || []).map((field, index) => ({
    ...field,
    key: field.fieldUid || `${field.fieldName || ''}/${index}`,
  })),
);
const pagedFields = computed(() => {
  const sorted = [...fields.value];
  if (fieldSortApplied.value)
    sorted.sort(
      (left, right) =>
        String(left.fieldUid || '').localeCompare(
          String(right.fieldUid || ''),
          'zh-CN',
          { numeric: true },
        ) * (fieldSortDescending.value ? -1 : 1),
    );
  return sorted.slice(
    (fieldsPage.value - 1) * fieldsPageSize.value,
    fieldsPage.value * fieldsPageSize.value,
  );
});
const canPublish = computed(() =>
  hasAccessByCodes(['modeling:model-materialized:publish']),
);
const request = useModelingRequest(
  () => modelId.value,
  () => {
    detail.value = undefined;
    executionOpen.value = false;
    loadError.value = false;
  },
);
const logsRequest = useModelingRequest(
  () => `${modelId.value}/${logsPage.value}/${logsPageSize.value}`,
  () => {
    logs.value = [];
    logsTotal.value = 0;
    logsError.value = false;
  },
);

function text(value: unknown) {
  return value === null || value === undefined || value === ''
    ? '-'
    : String(value);
}

function time(value?: number | string) {
  return value === undefined || value === ''
    ? '-'
    : formatDate(value, 'YYYY-MM-DD HH:mm');
}

function fieldValue(field: FieldRow, key: string) {
  if (key === 'creator' || key === 'createTime') return '-';
  if (key === 'dataLength')
    return text(field.dataLength ?? field.dataPrecision);
  return text(field[key as keyof ModelingModelApi.ModelField]);
}

function toggleColumn(key: string, type: 'fields' | 'logs') {
  const hidden = type === 'fields' ? hiddenFieldColumns : hiddenLogColumns;
  hidden.value = hidden.value.includes(key)
    ? hidden.value.filter((item) => item !== key)
    : [...hidden.value, key];
}

function sortFields(key: string) {
  if (key !== 'fieldUid') return;
  fieldSortApplied.value = true;
  fieldSortDescending.value = !fieldSortDescending.value;
  fieldsPage.value = 1;
}

async function load() {
  const id = modelId.value;
  const current = request.begin();
  if (!id) return;
  loading.value = true;
  loadError.value = false;
  try {
    const result = await getModelMaterializedByModel(id);
    if (!current()) return;
    detail.value = result;
    fieldsPage.value = Math.min(
      fieldsPage.value,
      Math.max(
        1,
        Math.ceil((result.fields?.length || 0) / fieldsPageSize.value),
      ),
    );
  } catch {
    if (current()) loadError.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

async function loadLogs() {
  const id = modelId.value;
  const current = logsRequest.begin();
  if (!id) return;
  logsLoading.value = true;
  logsError.value = false;
  try {
    const result = await getModelMaterializedLogPage({
      modelId: id,
      pageNo: logsPage.value,
      pageSize: logsPageSize.value,
    });
    if (!current()) return;
    logs.value = result.list;
    logsTotal.value = result.total;
  } catch {
    if (current()) logsError.value = true;
  } finally {
    if (current()) logsLoading.value = false;
  }
}

function publish() {
  if (!detail.value) return;
  if (detail.value.materializedId || detail.value.id)
    void publishModal.value?.openRepublish(detail.value);
  else void publishModal.value?.open(detail.value.modelId);
}

function back() {
  void router.push(
    typeof route.meta.activePath === 'string'
      ? route.meta.activePath
      : '/model/models/model-materialized',
  );
}

async function afterPublication() {
  await load();
  if (activeTab.value === 'logs') await loadLogs();
}

watch(
  [modelId, () => access.accessToken, () => access.refreshToken],
  () => {
    activeTab.value = 'fields';
    fieldsPage.value = 1;
    fieldSortApplied.value = false;
    logsPage.value = 1;
    void load();
  },
  { immediate: true },
);
watch([activeTab, logsPage, logsPageSize], () => {
  if (activeTab.value === 'logs') void loadLogs();
});
</script>

<template>
  <Page auto-content-height content-class="qd-materialized-detail-content">
    <div class="qd-logic-page qd-materialized qd-materialized-detail">
      <Alert v-if="!modelId" type="error" message="无效的模型编号" />
      <div
        v-else-if="loadError"
        class="qd-materialized-detail-error"
        role="alert"
      >
        发布模型详情加载失败
        <button class="qd-btn" type="button" @click="load">重新加载</button>
      </div>
      <Spin v-else :spinning="loading" class="qd-materialized-detail-spin">
        <template v-if="detail">
          <section class="qd-materialized-detail-summary">
            <div class="qd-materialized-detail-heading">
              <span class="qd-materialized-detail-id">{{
                detail.modelId
              }}</span>
              <h1>{{ detail.modelAlias || '-' }}</h1>
              <span
                class="qd-materialized-detail-status"
                :data-status="status"
                >{{ statusLabel }}</span>
              <button
                type="button"
                class="qd-btn qd-btn--primary is-plain qd-materialized-detail-back"
                @click="back"
              >
                <span
                  class="qd-materialized-detail-glyph"
                  :style="backMask"
                  aria-hidden="true"
                ></span>
                返回
              </button>
            </div>
            <table class="qd-materialized-summary-table">
              <colgroup>
                <col class="qd-materialized-summary-label" />
                <col />
                <col class="qd-materialized-summary-label" />
                <col />
                <col class="qd-materialized-summary-label" />
                <col />
              </colgroup>
              <tbody>
                <tr>
                  <th>英文名称</th>
                  <td>{{ detail.modelName || '-' }}</td>
                  <th>表类型</th>
                  <td>
                    <span
                      v-if="tableType"
                      class="qd-materialized-type-tag"
                      :data-type="detail.tableType"
                      ><img :src="tableType.infoIcon" alt="" />{{
                        tableType.label
                      }}</span><span v-else>{{ detail.tableType || '-' }}</span>
                  </td>
                  <th>归属信息</th>
                  <td>
                    <span class="qd-materialized-detail-belong"><img :src="sectionIcon" alt="" />{{ belonging }}</span>
                  </td>
                </tr>
                <tr>
                  <th>描述</th>
                  <td colspan="5">{{ detail.description || '-' }}</td>
                </tr>
                <tr>
                  <th>创建人</th>
                  <td>{{ detail.creatorName || detail.creator || '-' }}</td>
                  <th>创建时间</th>
                  <td>{{ time(detail.createTime) }}</td>
                  <th>更新时间</th>
                  <td>{{ time(detail.updateTime) }}</td>
                </tr>
              </tbody>
            </table>
          </section>
          <section class="qd-materialized-detail-body">
            <div
              class="qd-materialized-detail-tabs"
              role="tablist"
              aria-label="发布模型详情"
            >
              <button
                v-for="tab in [
                  { key: 'fields', name: '属性字段' },
                  { key: 'logs', name: '发布日志' },
                  { key: 'info', name: '详细信息' },
                ]"
                :id="`materialized-tab-${tab.key}`"
                :key="tab.key"
                type="button"
                role="tab"
                :aria-selected="activeTab === tab.key"
                :class="{ active: activeTab === tab.key }"
                @click="activeTab = tab.key"
              >
                {{ tab.name }}
              </button>
            </div>
            <div
              v-if="activeTab === 'fields'"
              role="tabpanel"
              aria-labelledby="materialized-tab-fields"
            >
              <div class="qd-materialized-detail-toolbar">
                <span></span>
                <div class="qd-materialized-detail-tools">
                  <button
                    type="button"
                    class="qd-btn qd-materialized-icon-button"
                    aria-label="刷新属性字段"
                    @click="load"
                  >
                    <QdIcon name="toolbarRefresh" />
                  </button>
                  <details class="qd-materialized-column-menu">
                    <summary
                      class="qd-btn qd-materialized-icon-button"
                      aria-label="属性字段列设置"
                    >
                      <QdIcon name="menu" />
                    </summary>
                    <div>
                      <label v-for="column in fieldColumns" :key="column.key"><input
                          type="checkbox"
                          :checked="!hiddenFieldColumns.includes(column.key)"
                          @change="toggleColumn(column.key, 'fields')"
                        />{{ column.title }}</label>
                    </div>
                  </details>
                </div>
              </div>
              <div class="qd-materialized-detail-table-scroll">
                <table
                  class="qd-materialized-detail-table qd-materialized-fields-table"
                >
                  <colgroup>
                    <col
                      v-for="column in visibleFieldColumns"
                      :key="column.key"
                      :style="{ width: column.width }"
                    />
                    <col style="width: 8%" />
                  </colgroup>
                  <thead>
                    <tr>
                      <th
                        v-for="column in visibleFieldColumns"
                        :key="column.key"
                      >
                        <button
                          v-if="column.key === 'fieldUid'"
                          type="button"
                          class="qd-materialized-sort-button"
                          @click="sortFields(column.key)"
                        >
                          {{ column.title
                          }}<span
                            class="qd-materialized-sort-arrows"
                            :class="{ descending: fieldSortDescending }"
                          ></span>
</button><span v-else>{{ column.title }}</span>
                      </th>
                      <th class="qd-materialized-detail-action">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="field in pagedFields" :key="field.key">
                      <td
                        v-for="column in visibleFieldColumns"
                        :key="column.key"
                        :title="fieldValue(field, column.key)"
                      >
                        <span
                          v-if="column.key === 'pkFlag'"
                          class="qd-materialized-readonly-switch"
                          :class="{ checked: field.pkFlag }"
                          role="img"
                          :aria-label="field.pkFlag ? '主键' : '非主键'"
                        ></span><span v-else>{{ fieldValue(field, column.key) }}</span>
                      </td>
                      <td class="qd-materialized-detail-action">
                        <button
                          type="button"
                          class="qd-materialized-text-action"
                          @click="fieldModal?.open(field)"
                        >
                          <QdIcon name="view" />详情
                        </button>
                      </td>
                    </tr>
                    <tr v-if="fields.length === 0">
                      <td
                        :colspan="visibleFieldColumns.length + 1"
                        class="qd-materialized-detail-empty"
                      >
                        暂无属性字段
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <ReferencePagination
                :page-no="fieldsPage"
                :page-size="fieldsPageSize"
                :total="fields.length"
                @change="
                  (pageNo, pageSize) => {
                    fieldsPage = pageNo;
                    fieldsPageSize = pageSize;
                  }
                "
              />
            </div>
            <div
              v-else-if="activeTab === 'logs'"
              role="tabpanel"
              aria-labelledby="materialized-tab-logs"
            >
              <div class="qd-materialized-detail-toolbar">
                <button
                  v-if="canPublish && !recoveryAction(detail)"
                  type="button"
                  class="qd-btn qd-btn--primary is-plain"
                  @click="publish"
                >
                  <span
                    class="qd-materialized-detail-glyph"
                    :style="materializeMask"
                    aria-hidden="true"
                  ></span>发布模型
</button><button
                  v-else-if="canPublish && recoveryAction(detail)"
                  type="button"
                  class="qd-btn qd-btn--primary is-plain"
                  @click="recoveryModal?.open(detail)"
                >
                  核验结果
</button><span v-else></span>
                <div class="qd-materialized-detail-tools">
                  <button
                    type="button"
                    class="qd-btn qd-materialized-icon-button"
                    aria-label="刷新发布日志"
                    @click="loadLogs"
                  >
                    <QdIcon name="toolbarRefresh" />
                  </button>
                  <details class="qd-materialized-column-menu">
                    <summary
                      class="qd-btn qd-materialized-icon-button"
                      aria-label="发布日志列设置"
                    >
                      <QdIcon name="menu" />
                    </summary>
                    <div>
                      <label v-for="column in logColumns" :key="column.key"><input
                          type="checkbox"
                          :checked="!hiddenLogColumns.includes(column.key)"
                          @change="toggleColumn(column.key, 'logs')"
                        />{{ column.title }}</label>
                    </div>
                  </details>
                </div>
              </div>
              <div
                v-if="logsError"
                class="qd-materialized-detail-error"
                role="alert"
              >
                发布日志加载失败
                <button class="qd-btn" type="button" @click="loadLogs">
                  重新加载
                </button>
              </div>
              <Spin v-else :spinning="logsLoading">
                <div class="qd-materialized-detail-table-scroll">
                  <table
                    class="qd-materialized-detail-table qd-materialized-logs-table"
                  >
                    <colgroup>
                      <col
                        v-for="column in visibleLogColumns"
                        :key="column.key"
                        :style="{ width: column.width }"
                      />
                    </colgroup>
                    <thead>
                      <tr>
                        <th
                          v-for="column in visibleLogColumns"
                          :key="column.key"
                        >
                          {{ column.title }}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="log in logs" :key="String(log.id)">
                        <td
                          v-for="column in visibleLogColumns"
                          :key="column.key"
                        >
                          <span
                            v-if="column.key === 'publishMode'"
                            class="qd-materialized-mode-tag"
                            >{{
                              log.publishMode === 'REBUILD'
                                ? '删除重建'
                                : log.publishMode === 'INCREMENT'
                                  ? '增量发布'
                                  : text(log.publishMode)
                            }}</span><span
                            v-else-if="column.key === 'status'"
                            class="qd-materialized-detail-status"
                            :data-status="log.status"
                            >{{
                              MATERIALIZED_STATUS_OPTIONS.find(
                                (item) => item.value === log.status,
                              )?.label || text(log.status)
                            }}</span><span v-else-if="column.key === 'createTime'">{{
                            time(log.createTime)
                          }}</span><button
                            v-else-if="column.key === 'message'"
                            type="button"
                            class="qd-materialized-log-message"
                            :title="log.message"
                            @click="
                              executionLog = log;
                              executionOpen = true;
                            "
                          >
                            {{ text(log.message) }}
</button><span
                            v-else
                            :title="
                              text(
                                log[
                                  column.key as keyof ModelMaterializedApi.LogItem
                                ],
                              )
                            "
                            >{{
                              text(
                                log[
                                  column.key as keyof ModelMaterializedApi.LogItem
                                ],
                              )
                            }}</span>
                        </td>
                      </tr>
                      <tr v-if="logs.length === 0">
                        <td
                          :colspan="visibleLogColumns.length"
                          class="qd-materialized-detail-empty"
                        >
                          暂无发布日志
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </Spin>
              <ReferencePagination
                :page-no="logsPage"
                :page-size="logsPageSize"
                :total="logsTotal"
                @change="
                  (pageNo, pageSize) => {
                    logsPage = pageNo;
                    logsPageSize = pageSize;
                  }
                "
              />
            </div>
            <div
              v-else
              role="tabpanel"
              aria-labelledby="materialized-tab-info"
              class="qd-materialized-detail-info"
            >
              <table
                class="qd-materialized-summary-table qd-materialized-info-table"
              >
                <colgroup>
                  <col class="qd-materialized-info-label" />
                  <col />
                  <col class="qd-materialized-info-label" />
                  <col />
                </colgroup>
                <tbody>
                  <tr>
                    <th>命名大小写</th>
                    <td>
                      <span
                        v-if="detail.tableCase"
                        class="qd-materialized-case-tag"
                        >{{ detail.tableCase === 2 ? '小写' : '大写' }}</span><span v-else>-</span>
                    </td>
                    <th>表命名规范</th>
                    <td>
                      {{ detail.tableNameNotation || detail.tableName || '-' }}
                    </td>
                  </tr>
                  <tr>
                    <th>标准类型</th>
                    <td>
                      {{
                        getDictLabel(
                          DICT_TYPE.STANDARD_STD_TYPE,
                          detail.documentType,
                        ) ||
                        detail.documentType ||
                        '-'
                      }}
                    </td>
                    <th>标准登记</th>
                    <td>{{ detail.documentId || '-' }}</td>
                  </tr>
                  <tr>
                    <th>创建人</th>
                    <td>{{ detail.creatorName || detail.creator || '-' }}</td>
                    <th>联系方式</th>
                    <td>{{ detail.creatorPhone || '-' }}</td>
                  </tr>
                  <tr>
                    <th>更新人</th>
                    <td>{{ detail.updaterName || detail.updater || '-' }}</td>
                    <th>联系方式</th>
                    <td>{{ detail.updaterPhone || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          <FieldDetailModal ref="fieldModal" />
        </template>
      </Spin>
      <PublishModal ref="publishModal" @success="afterPublication" />
      <RecoveryModal ref="recoveryModal" @refresh="afterPublication" />
      <Modal
        v-model:open="executionOpen"
        title="执行日志信息"
        :width="800"
        :footer="null"
        destroy-on-hidden
      >
        <p>{{ executionLog?.message || '-' }}</p>
        <pre class="qd-materialized-execution-ddl">{{
          executionLog?.ddlSql || '-'
        }}</pre>
      </Modal>
    </div>
  </Page>
</template>
