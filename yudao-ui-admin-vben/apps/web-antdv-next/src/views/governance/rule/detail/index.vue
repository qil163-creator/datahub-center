<script lang="ts" setup>
import type { SafetyRuleWorkbenchApi as Api } from '#/api/governance/rule/workbench';
import type { SafetyColumn } from '#/views/governance/common/safety-workbench.vue';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page, useVbenModal } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';

import { Alert, Button, message, Spin, Tabs, Tag } from 'antdv-next';

import { deleteSensitiveList } from '#/api/asset/sensitive';
import {
  getSafetyRule,
  getSafetyRuleSensitiveRows,
} from '#/api/governance/rule/workbench';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import SensitiveDetail from '#/views/asset/sensitive/modules/detail.vue';
import { useSensitiveAccess } from '#/views/asset/sensitive/use-sensitive-access';
import { useBatchConfirm as useOwnedConfirm } from '#/views/dataforge/batchfile/use-confirm';
import ReferenceLevel from '#/views/governance/category/components/reference-level.vue';
import {
  categoryIcon,
  categoryTime,
  validCategoryRevision,
} from '#/views/governance/category/reference-data';
import { useCategorySession } from '#/views/governance/category/use-session';
import SafetyWorkbench from '#/views/governance/common/safety-workbench.vue';

import { ruleDisplayId, safetySort, SCENE_LABELS } from '../reference-data';

import '../reference.css';

defineOptions({ name: 'GovernanceSafetyRuleDetail' });
const route = useRoute();
const router = useRouter();
const tabs = useTabs();
const { identity, can } = useCategorySession();
const { can: canSensitive } = useSensitiveAccess();
const [SensitiveDetailModal, sensitiveDetailApi] = useVbenModal({
  connectedComponent: SensitiveDetail,
  destroyOnClose: true,
});
const id = computed(() => parseResourceId(route.params.id));
const allowed = computed(() => can('query', 'rule'));
let active = true;
let epoch = 0;
const confirmationEpoch = ref(0);
const confirm = useOwnedConfirm(
  computed(() =>
    JSON.stringify([identity.value, id.value, confirmationEpoch.value]),
  ),
);
const pending = reactive(new Map<string, symbol>());
const row = ref<Api.Rule>();
const rows = ref<Api.SensitiveRow[]>([]);
const loading = ref(false);
const tableLoading = ref(false);
const error = ref('');
const tableError = ref('');
const tab = ref('sensitive');
const query = reactive<{
  pageNo: number;
  pageSize: number;
  sortField: 'createTime' | 'id';
  sortOrder: 'asc' | 'desc';
}>({ pageNo: 1, pageSize: 10, sortField: 'createTime', sortOrder: 'desc' });
const total = ref(0);
const guard = createLatestWins();
const tableGuard = createLatestWins();
const emptyImage = '/assets/qdata-map/empty-search.png';
const columns: SafetyColumn[] = [
  { key: 'id', title: '编号', width: 60, align: 'center', sorter: true },
  { key: 'assetName', title: '资产名称/描述', minWidth: 180, align: 'left' },
  { key: 'tableName', title: '表名', minWidth: 200, align: 'left' },
  { key: 'columnName', title: '字段名', minWidth: 150, align: 'left' },
  { key: 'dataLevel', title: '数据分级', width: 120, align: 'center' },
  { key: 'status', title: '状态', minWidth: 120, align: 'center' },
  { key: 'creatorName', title: '创建人', width: 120, align: 'center' },
  {
    key: 'createTime',
    title: '创建时间',
    width: 150,
    align: 'center',
    sorter: true,
  },
  {
    key: 'actions',
    title: '操作',
    width: 200,
    align: 'center',
    fixed: 'right',
  },
];
function clear() {
  epoch++;
  confirmationEpoch.value++;
  pending.clear();
  if (typeof sensitiveDetailApi.close === 'function')
    sensitiveDetailApi.close();
  guard.invalidate();
  tableGuard.invalidate();
  row.value = undefined;
  rows.value = [];
  loading.value = false;
  tableLoading.value = false;
  error.value = '';
  tableError.value = '';
  total.value = 0;
  query.pageNo = 1;
  tab.value = 'sensitive';
}
async function loadSensitive() {
  if (
    !active ||
    !allowed.value ||
    !id.value ||
    !row.value ||
    tab.value !== 'sensitive'
  )
    return;
  const ticket = tableGuard.next();
  tableLoading.value = true;
  tableError.value = '';
  try {
    const data = await getSafetyRuleSensitiveRows({
      ...query,
      ruleId: id.value,
    });
    if (!ticket.isCurrent()) return;
    rows.value = data.list;
    total.value = data.total;
  } catch {
    if (ticket.isCurrent()) {
      rows.value = [];
      total.value = 0;
      tableError.value = '敏感清单读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) tableLoading.value = false;
  }
}
async function load() {
  clear();
  if (!active || !allowed.value || !id.value) return;
  const ticket = guard.next();
  loading.value = true;
  try {
    const data = await getSafetyRule(id.value);
    if (!ticket.isCurrent()) return;
    if (resourceIdKey(data.id) !== id.value) throw new Error('RULE_IDENTITY');
    row.value = data;
    await loadSensitive();
  } catch {
    if (ticket.isCurrent()) error.value = '脱敏规则详情读取失败，请重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function showSensitive(record: Api.SensitiveRow) {
  if (canSensitive('query') && active)
    sensitiveDetailApi.setData({ id: record.id }).open();
}
function removeSensitive(record: Api.SensitiveRow) {
  if (
    !active ||
    !canSensitive('delete') ||
    record.status !== 1 ||
    !parseResourceId(record.id) ||
    !validCategoryRevision(record.revision)
  )
    return;
  const captured = epoch;
  const key = resourceIdKey(record.id);
  confirm({
    title: '提示',
    content: `是否确认删除编号为“${record.sourceRecordId || key}”的数据项？`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (
        !active ||
        captured !== epoch ||
        !canSensitive('delete') ||
        pending.has(key)
      )
        return;
      const token = Symbol(key);
      pending.set(key, token);
      try {
        await deleteSensitiveList(record.id, record.revision);
        if (!active || captured !== epoch) return;
        message.success('删除成功');
        await loadSensitive();
      } catch {
        if (active && captured === epoch)
          tableError.value = '删除失败，敏感清单可能已变化，请刷新后重试。';
      } finally {
        if (pending.get(key) === token) pending.delete(key);
      }
    },
  });
}
function sort(value: unknown) {
  Object.assign(query, safetySort(value), { pageNo: 1 });
  void loadSensitive();
}
function back() {
  void tabs.closeCurrentTab();
  void router.push({
    path: String(route.meta.activePath || '/govern/safety/rule'),
  });
}
watch(
  [identity, id],
  () => {
    if (active) void load();
    else clear();
  },
  { immediate: true, flush: 'sync' },
);
watch(tab, (value) => {
  if (value === 'sensitive') void loadSensitive();
});
onActivated(() => {
  if (!active) {
    active = true;
    void load();
  }
});
onDeactivated(() => {
  active = false;
  clear();
});
onBeforeUnmount(() => {
  active = false;
  clear();
});
</script>
<template>
  <Page auto-content-height>
    <div class="safety-rule-detail">
      <Alert
        v-if="!allowed"
        message="暂无脱敏规则查看权限"
        type="warning"
      /><Alert v-else-if="error" :message="error" type="error">
        <template #action>
          <Button @click="load">重试</Button>
        </template> </Alert
      ><Spin :spinning="loading">
        <section v-if="row" class="safety-rule-overview">
          <header class="safety-rule-heading">
            <span class="safety-rule-id">{{ ruleDisplayId(row) }}</span
            ><strong>{{ row.ruleName }}</strong
            ><Tag class="category-soft-tag">
              {{ row.status === 0 ? '启用' : '禁用' }} </Tag
            ><DetailBackButton @click="back" />
          </header>
          <table>
            <tbody>
              <tr>
                <td class="label">数据分类</td>
                <td>{{ row.categoryName || '-' }}</td>
                <td class="label">应用场景</td>
                <td>
                  <span class="safety-scene-tags"
                    ><Tag
                      v-for="scene in row.applicationScene"
                      :key="scene"
                      class="category-soft-tag"
                      >{{ SCENE_LABELS[scene] || scene }}</Tag
                    ></span
                  >
                </td>
                <td class="label">脱敏方式</td>
                <td><Tag class="category-soft-tag">展示脱敏</Tag></td>
              </tr>
              <tr>
                <td class="label">描述</td>
                <td colspan="5">{{ row.description || '-' }}</td>
              </tr>
              <tr>
                <td class="label">创建人</td>
                <td>{{ row.creatorName || '-' }}</td>
                <td class="label">创建时间</td>
                <td>{{ categoryTime(row.createTime) }}</td>
                <td class="label">更新时间</td>
                <td>{{ categoryTime(row.updateTime) }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </Spin>
      <section v-if="row" class="safety-rule-content">
        <Tabs
          v-model:active-key="tab"
          :items="[
            { key: 'sensitive', label: '敏感清单' },
            { key: 'info', label: '详细信息' },
          ]"
        /><SafetyWorkbench
          v-if="tab === 'sensitive'"
          v-model:page="query.pageNo"
          v-model:page-size="query.pageSize"
          :rows="rows"
          :columns="columns"
          :total="total"
          :loading="tableLoading"
          :error="tableError"
          :min-width="1300"
          show-search
          height="300px"
          compact
          filters-collapsed
          @refresh="loadSensitive"
          @change="loadSensitive"
          @sort="sort"
        >
          <template #empty>
            <div class="safety-rule-empty">
              <img :src="emptyImage" alt="" /><span>暂无记录</span>
            </div> </template
          ><template #bodyCell="{ column, record }">
            <span v-if="column.key === 'id'">{{
              record.sourceRecordId || resourceIdKey(record.id)
            }}</span>
            <div
              v-else-if="column.key === 'assetName'"
              class="category-name-cell"
            >
              <span>{{ record.assetName || '-' }}</span>
              <p>{{ record.assetDescription || '-' }}</p>
            </div>
            <span v-else-if="column.key === 'tableName'">{{
              record.tableName || '-'
            }}</span
            ><span v-else-if="column.key === 'columnName'">{{
              record.columnName || '-'
            }}</span
            ><ReferenceLevel
              v-else-if="column.key === 'dataLevel'"
              :level="record.levelName"
              :color="record.levelColor"
            /><Tag
              v-else-if="column.key === 'status'"
              class="category-soft-tag"
            >
              {{ record.status === 0 ? '启用' : '禁用' }} </Tag
            ><span v-else-if="column.key === 'creatorName'">{{
              record.creatorName || '-'
            }}</span
            ><span v-else-if="column.key === 'createTime'">{{
              categoryTime(record.createTime)
            }}</span>
            <div
              v-else-if="column.key === 'actions'"
              class="category-row-actions"
            >
              <Button
                v-if="canSensitive('query')"
                type="link"
                @click="showSensitive(record)"
              >
                <i
                  class="category-icon"
                  :style="{ maskImage: `url(${categoryIcon('detail')})` }"
                ></i
                >详情 </Button
              ><Button
                v-if="canSensitive('delete')"
                type="link"
                danger
                :disabled="
                  record.status !== 1 || !validCategoryRevision(record.revision)
                "
                :loading="pending.has(resourceIdKey(record.id))"
                @click="removeSensitive(record)"
              >
                <i
                  class="category-icon"
                  :style="{ maskImage: `url(${categoryIcon('delete')})` }"
                ></i
                >删除
              </Button>
            </div>
          </template>
        </SafetyWorkbench>
        <table v-else class="safety-rule-info">
          <tbody>
            <tr>
              <td class="label">创建人</td>
              <td>{{ row.creatorName || '-' }}</td>
              <td class="label">创建时间</td>
              <td>{{ categoryTime(row.createTime) }}</td>
            </tr>
            <tr>
              <td class="label">更新人</td>
              <td>{{ row.updaterName || '-' }}</td>
              <td class="label">更新时间</td>
              <td>{{ categoryTime(row.updateTime) }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>
    <SensitiveDetailModal variant="rule" />
  </Page>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
