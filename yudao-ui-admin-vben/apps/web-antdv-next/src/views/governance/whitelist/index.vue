<script lang="ts" setup>
import type { SafetyWhitelistWorkbenchApi as Api } from '#/api/governance/whitelist/workbench';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';

import { Page } from '@vben/common-ui';

import { Button, message, Switch } from 'antdv-next';

import { ACTION_ICON, TableAction } from '#/adapter/business-vxe-table';
import {
  changeWhitelistWorkbenchStatus,
  deleteWhitelistWorkbench,
  deleteWhitelistWorkbenchList,
  getWhitelistWorkbenchOptions,
  getWhitelistWorkbenchPage,
} from '#/api/governance/whitelist/workbench';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { createLatestWins, resourceIdKey } from '#/utils/resource-id';
import { useBatchConfirm as useOwnedConfirm } from '#/views/dataforge/batchfile/use-confirm';
import {
  CATEGORY_STATUS_OPTIONS,
  categoryIcon,
  categoryTime,
} from '#/views/governance/category/reference-data';
import { useCategorySession } from '#/views/governance/category/use-session';
import NativeSearch from '#/views/governance/common/native-search.vue';
import SafetyWorkbench from '#/views/governance/common/safety-workbench.vue';
import { safetySort } from '#/views/governance/rule/reference-data';

import Dialog from './modules/workbench-form.vue';
import {
  WHITELIST_COLUMNS,
  whitelistCategoryTree,
  whitelistDisplayId,
  whitelistPeriod,
  whitelistUserNames,
  whitelistVersion,
} from './reference-data';

import '#/views/governance/rule/reference.css';
import './reference.css';

defineOptions({ name: 'SafetyWhitelist' });
const { identity, can } = useCategorySession();
const allowed = computed(() => can('query', 'whitelist'));
const active = ref(true);
let alive = true;
let epoch = 0;
const confirm = useOwnedConfirm(
  computed(() => JSON.stringify([identity.value, active.value])),
);
const rows = ref<Api.Row[]>([]);
const options = ref<Api.Options>({ directories: [], categories: [] });
const selected = ref<string[]>([]);
const total = ref(0);
const loading = ref(false);
const error = ref('');
const optionsError = ref('');
const categoryTree = computed(() =>
  whitelistCategoryTree(options.value, false),
);
const filter = reactive<{
  categoryId?: Api.Id;
  name?: string;
  status?: number;
}>({});
const query = reactive<Api.PageReq & { pageNo: number; pageSize: number }>({
  pageNo: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'desc',
});
const pending = reactive(new Map<string, symbol>());
const dialog = ref<{ id?: Api.Id; readonly?: boolean }>();
const listGuard = createLatestWins();
const optionsGuard = createLatestWins();
function clear() {
  epoch++;
  listGuard.invalidate();
  optionsGuard.invalidate();
  rows.value = [];
  options.value = { directories: [], categories: [] };
  selected.value = [];
  pending.clear();
  total.value = 0;
  loading.value = false;
  error.value = '';
  optionsError.value = '';
  dialog.value = undefined;
}
async function loadRows() {
  if (!active.value || !alive || !allowed.value) return;
  const ticket = listGuard.next();
  loading.value = true;
  error.value = '';
  try {
    const data = await getWhitelistWorkbenchPage({ ...query });
    if (!ticket.isCurrent()) return;
    if (query.pageNo > 1 && data.list.length === 0 && data.total > 0) {
      query.pageNo = Math.max(1, Math.ceil(data.total / query.pageSize));
      void loadRows();
      return;
    }
    rows.value = data.list;
    total.value = data.total;
    selected.value = [];
  } catch {
    if (ticket.isCurrent()) {
      rows.value = [];
      total.value = 0;
      error.value = '脱敏白名单读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
async function loadOptions() {
  if (!active.value || !alive || !allowed.value) return;
  const ticket = optionsGuard.next();
  try {
    const data = await getWhitelistWorkbenchOptions();
    if (ticket.isCurrent()) {
      options.value = data;
      optionsError.value = '';
    }
  } catch {
    if (ticket.isCurrent())
      optionsError.value = '数据分类选项读取失败，请重试。';
  }
}
async function refresh() {
  await loadOptions();
  await loadRows();
}
function search() {
  Object.assign(query, {
    ...filter,
    name: filter.name?.trim() || undefined,
    pageNo: 1,
  });
  void loadRows();
}
function reset() {
  filter.name = undefined;
  filter.categoryId = undefined;
  filter.status = undefined;
  search();
}
function sort(value: unknown) {
  Object.assign(query, safetySort(value), { pageNo: 1 });
  void loadRows();
}
function edit(row?: Api.Row) {
  if (can(row ? 'update' : 'create', 'whitelist'))
    dialog.value = { id: row?.id };
}
function detail(row: Api.Row) {
  if (allowed.value) dialog.value = { id: row.id, readonly: true };
}
function remove(row?: Api.Row) {
  if (!can('delete', 'whitelist') || row?.status === 0) return;
  const all = row
    ? [row]
    : rows.value.filter((item) =>
        selected.value.includes(resourceIdKey(item.id)),
      );
  const chosen = all.filter((item) => item.status === 1);
  const items = chosen.map((item) => whitelistVersion(item));
  if (all.length === 0 || items.some((item) => !item)) return;
  if (chosen.length === 0) {
    message.warning(`可删除0个，不可删除${all.length}个，请先禁用白名单。`);
    return;
  }
  const captured = epoch;
  confirm({
    title: '提示',
    content: row
      ? `是否确认删除编号为${whitelistDisplayId(row)} 的数据项？`
      : `可删除${chosen.length}个，不可删除${all.length - chosen.length}个，是否删除可删部分`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (captured !== epoch || !active.value || !can('delete', 'whitelist'))
        return;
      try {
        await (row
          ? deleteWhitelistWorkbench(items[0]!)
          : deleteWhitelistWorkbenchList(items as Api.Version[]));
        if (captured !== epoch || !active.value) return;
        message.success('删除成功');
        await loadRows();
      } catch {
        if (captured === epoch && active.value)
          error.value = '删除失败，白名单可能已变化，请刷新后重试。';
      }
    },
  });
}
function changeStatus(row: Api.Row, checked: boolean) {
  const version = whitelistVersion(row);
  const key = resourceIdKey(row.id);
  const captured = epoch;
  if (!version || !can('update', 'whitelist') || pending.has(key)) return;
  confirm({
    title: '提示',
    content: `确认要“${checked ? '有效' : '禁用'}”、“${row.name}”吗？`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (
        captured !== epoch ||
        !active.value ||
        !can('update', 'whitelist') ||
        pending.has(key)
      )
        return;
      const token = Symbol(key);
      pending.set(key, token);
      try {
        await changeWhitelistWorkbenchStatus({
          ...version,
          status: checked ? 0 : 1,
        });
        if (captured !== epoch || !active.value) return;
        message.success('操作成功');
        await loadRows();
      } catch {
        if (captured === epoch && active.value)
          error.value = '状态修改失败，请刷新白名单后重试。';
      } finally {
        if (pending.get(key) === token) pending.delete(key);
      }
    },
  });
}
function saved() {
  message.success('保存成功');
  void refresh();
}
watch(
  identity,
  () => {
    clear();
    if (active.value) void refresh();
  },
  { flush: 'sync' },
);
onMounted(() => {
  void refresh();
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void refresh();
  }
});
onDeactivated(() => {
  active.value = false;
  clear();
});
onBeforeUnmount(() => {
  alive = false;
  active.value = false;
  clear();
});
</script>
<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
  >
    <div v-if="!allowed" class="safety-access-denied">
      暂无脱敏白名单查看权限
    </div>
    <SafetyWorkbench
      v-else
      v-model:page="query.pageNo"
      v-model:page-size="query.pageSize"
      v-model:selected="selected"
      :rows="rows"
      :columns="WHITELIST_COLUMNS"
      title="脱敏白名单列表"
      :total="total"
      :loading="loading"
      :error="error || optionsError"
      :min-width="1655"
      selection
      :selection-width="55"
      class="safety-whitelist-workbench"
      @change="loadRows"
      @refresh="refresh"
      @sort="sort"
      @detail="detail"
    >
      <template #filters>
        <NativeSearch
          :values="filter"
          icons
          :schema="[
            {
              fieldName: 'name',
              label: '白名单名称',
              component: 'Input',
              componentProps: {
                allowClear: true,
                placeholder: '请输入白名单名称',
              },
            },
            {
              fieldName: 'categoryId',
              label: '数据分类',
              component: 'TreeSelect',
              componentProps: {
                allowClear: true,
                placeholder: '请选择数据分类',
                treeData: categoryTree,
                treeDefaultExpandAll: true,
                showSearch: true,
                treeNodeFilterProp: 'title',
              },
            },
            {
              fieldName: 'status',
              label: '状态',
              component: 'Select',
              componentProps: {
                allowClear: true,
                placeholder: '请选择状态',
                options: CATEGORY_STATUS_OPTIONS,
              },
            },
          ]"
          @values="Object.assign(filter, $event)"
          @submit="search"
          @reset="reset"
        />
</template><template #actions>
        <TableAction
:actions="[
          { label: '新增', type: 'primary', icon: ACTION_ICON.ADD, ifShow: can('create', 'whitelist'), onClick: () => edit() },
          { label: '批量删除', type: 'primary', danger: true, icon: ACTION_ICON.DELETE, ifShow: can('delete', 'whitelist'), disabled: !selected.length, onClick: () => remove() },
        ]"
/>
      </template>
      <template #bodyCell="{ column, record }">
        <span v-if="column.key === 'id'">{{ whitelistDisplayId(record) }}</span>
        <div
          v-else-if="column.key === 'name'"
          class="category-name-cell safety-rule-name"
        >
          <span :title="record.name">{{ record.name }}</span>
          <TextEllipsisCell
            :content="record.description || '-'"
            :line="2"
            center
          />
        </div>
        <span v-else-if="column.key === 'categoryName'">{{
          record.categoryName || '-'
        }}</span><span v-else-if="column.key === 'effectiveCategory'">用户</span><span
          v-else-if="column.key === 'users'"
          :title="whitelistUserNames(record.users)"
          >{{ whitelistUserNames(record.users) }}</span><span v-else-if="column.key === 'period'">{{
          whitelistPeriod(record)
        }}</span><Switch
          v-else-if="column.key === 'status'"
          :checked="record.status === 0"
          :disabled="!can('update', 'whitelist') || !whitelistVersion(record)"
          :loading="pending.has(resourceIdKey(record.id))"
          @change="(checked) => changeStatus(record, !!checked)"
        /><span v-else-if="column.key === 'creatorName'">{{
          record.creatorName || '-'
        }}</span><span v-else-if="column.key === 'createTime'">{{
          categoryTime(record.createTime)
        }}</span>
        <div v-else-if="column.key === 'actions'" class="category-row-actions">
          <Button type="link" @click="detail(record)">
            <i
              class="category-icon"
              :style="{ maskImage: `url(${categoryIcon('detail')})` }"
            ></i>详情
</Button><Button
            v-if="can('update', 'whitelist')"
            type="link"
            @click="edit(record)"
          >
            <i
              class="category-icon"
              :style="{ maskImage: `url(${categoryIcon('edit')})` }"
            ></i>修改
</Button><Button
            v-if="can('delete', 'whitelist')"
            type="link"
            danger
            :disabled="record.status === 0"
            @click="remove(record)"
          >
            <i
              class="category-icon"
              :style="{ maskImage: `url(${categoryIcon('delete')})` }"
            ></i>删除
          </Button>
        </div>
      </template>
</SafetyWorkbench><Dialog
      v-if="dialog"
      :key="`${dialog.id || 'create'}:${dialog.readonly || false}`"
      v-bind="dialog"
      @close="dialog = undefined"
      @success="saved"
    />
  </Page>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
