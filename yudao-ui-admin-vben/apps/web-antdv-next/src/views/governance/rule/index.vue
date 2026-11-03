<script lang="ts" setup>
import type { SafetyRuleWorkbenchApi as Api } from '#/api/governance/rule/workbench';

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
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { Button, message, Switch, Tag } from 'antdv-next';

import {
  changeSafetyRuleStatus,
  getSafetyRuleOptions,
  getSafetyRules,
  removeSafetyRule,
  removeSafetyRules,
} from '#/api/governance/rule/workbench';
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

import RuleForm from './modules/workbench-form.vue';
import {
  RULE_COLUMNS,
  ruleCategoryTree,
  ruleDisplayId,
  ruleVersion,
  safetySort,
  SCENE_LABELS,
} from './reference-data';

import './reference.css';

defineOptions({ name: 'SafetyRule' });
const router = useRouter();
const { identity, can } = useCategorySession();
const allowed = computed(() => can('query', 'rule'));
const active = ref(true);
let alive = true;
let epoch = 0;
const confirm = useOwnedConfirm(
  computed(() => JSON.stringify([identity.value, active.value])),
);
const rows = ref<Api.Rule[]>([]);
const options = ref<Api.Options>({ directories: [], categories: [] });
const categoryTree = computed(() =>
  ruleCategoryTree({
    ...options.value,
    categories: options.value.categories.map((item) => ({
      ...item,
      configured: false,
    })),
  }),
);
const selected = ref<string[]>([]);
const loading = ref(false);
const error = ref('');
const optionsError = ref('');
const filter = reactive<{
  categoryId?: Api.Id;
  ruleName?: string;
  status?: number;
}>({});
const query = reactive<Api.PageReq & { pageNo: number; pageSize: number }>({
  pageNo: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'desc',
});
const total = ref(0);
const pending = reactive(new Map<string, symbol>());
const editing = ref(false);
const editId = ref<Api.Id>();
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
  editId.value = undefined;
  editing.value = false;
  total.value = 0;
  loading.value = false;
  error.value = '';
  optionsError.value = '';
}
async function loadRows() {
  if (!active.value || !alive || !allowed.value) return;
  const ticket = listGuard.next();
  loading.value = true;
  error.value = '';
  try {
    const data = await getSafetyRules({ ...query });
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
      error.value = '脱敏规则读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
async function loadOptions() {
  if (!active.value || !alive || !allowed.value) return;
  const ticket = optionsGuard.next();
  try {
    const data = await getSafetyRuleOptions();
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
    ruleName: filter.ruleName?.trim() || undefined,
    pageNo: 1,
  });
  void loadRows();
}
function reset() {
  filter.ruleName = undefined;
  filter.categoryId = undefined;
  filter.status = undefined;
  search();
}
function sort(value: unknown) {
  Object.assign(query, safetySort(value), { pageNo: 1 });
  void loadRows();
}
function edit(row?: Api.Rule) {
  if (!can(row ? 'update' : 'create', 'rule')) return;
  editId.value = row?.id;
  editing.value = true;
}
function detail(row: Api.Rule) {
  if (allowed.value)
    void router.push({
      name: 'GovernanceSafetyRuleDetail',
      params: { id: resourceIdKey(row.id) },
    });
}
function remove(row?: Api.Rule) {
  if (!can('delete', 'rule')) return;
  if (row?.status === 0) return;
  const all = row
    ? [row]
    : rows.value.filter((item) =>
        selected.value.includes(resourceIdKey(item.id)),
      );
  const chosen = all.filter((item) => item.status === 1);
  const items = chosen.map((item) => ruleVersion(item));
  if (items.length === 0 || items.some((item) => !item)) return;
  const captured = epoch;
  confirm({
    title: '提示',
    content: row
      ? `是否确认删除编号为${ruleDisplayId(row)} 的数据项？`
      : `可删除${chosen.length}个，不可删除${all.length - chosen.length}个，是否删除可删部分`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (captured !== epoch || !active.value || !can('delete', 'rule')) return;
      try {
        await (row
          ? removeSafetyRule(items[0]!)
          : removeSafetyRules(items as Api.Version[]));
        if (captured !== epoch || !active.value) return;
        message.success('删除成功');
        await loadRows();
      } catch {
        if (captured === epoch && active.value)
          error.value = '删除失败，规则可能已变化或存在引用，请刷新后重试。';
      }
    },
  });
}
function changeStatus(row: Api.Rule, checked: boolean) {
  const version = ruleVersion(row);
  const key = resourceIdKey(row.id);
  const captured = epoch;
  if (!version || !can('update', 'rule') || pending.has(key)) return;
  confirm({
    title: '提示',
    content: `确认要${checked ? '启用' : '禁用'}“${row.ruleName}”规则吗？`,
    okText: '确定',
    cancelText: '取消',
    async onOk() {
      if (
        captured !== epoch ||
        !active.value ||
        !can('update', 'rule') ||
        pending.has(key)
      )
        return;
      const token = Symbol(key);
      pending.set(key, token);
      try {
        await changeSafetyRuleStatus({ ...version, status: checked ? 0 : 1 });
        if (captured !== epoch || !active.value) return;
        message.success('操作成功');
        await loadRows();
      } catch {
        if (captured === epoch && active.value)
          error.value = '状态修改失败，请刷新规则后重试。';
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
  <Page auto-content-height>
    <div v-if="!allowed" class="safety-access-denied">暂无脱敏规则查看权限</div>
    <SafetyWorkbench
      v-else
      v-model:page="query.pageNo"
      v-model:page-size="query.pageSize"
      v-model:selected="selected"
      :rows="rows"
      :columns="RULE_COLUMNS"
      title="脱敏规则列表"
      :loading="loading"
      :error="error || optionsError"
      :total="total"
      selection
      :min-width="1375"
      @refresh="refresh"
      @change="loadRows"
      @sort="sort"
      @detail="detail"
    >
      <template #filters>
        <NativeSearch
          :values="filter"
          icons
          :schema="[
            {
              fieldName: 'ruleName',
              label: '脱敏规则名称',
              component: 'Input',
              componentProps: {
                allowClear: true,
                placeholder: '请输入脱敏规则名称',
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
      </template>
      <template #actions>
        <Button v-if="can('create', 'rule')" type="primary" @click="edit()">
          <i
            class="category-icon"
            :style="{ maskImage: `url(${categoryIcon('plus')})` }"
          ></i
          >新增 </Button
        ><Button
          v-if="can('delete', 'rule')"
          danger
          :disabled="selected.length === 0"
          @click="remove()"
        >
          <i
            class="category-icon"
            :style="{ maskImage: `url(${categoryIcon('delete')})` }"
          ></i
          >删除
        </Button>
      </template>
      <template #bodyCell="{ column, record }">
        <span v-if="column.key === 'id'">{{ ruleDisplayId(record) }}</span>
        <div
          v-else-if="column.key === 'ruleName'"
          class="category-name-cell safety-rule-name"
        >
          <span :title="record.ruleName">{{ record.ruleName }}</span>
          <TextEllipsisCell
            :content="record.description || '-'"
            :line="2"
            center
          />
        </div>
        <span v-else-if="column.key === 'categoryName'">{{
          record.categoryName || '-'
        }}</span
        ><span
          v-else-if="column.key === 'applicationScene'"
          class="safety-scene-tags"
          ><Tag
            v-for="scene in record.applicationScene"
            :key="scene"
            class="category-soft-tag"
            >{{ SCENE_LABELS[scene] || scene }}</Tag
          ></span
        ><Tag v-else-if="column.key === 'maskType'" class="category-soft-tag">
          展示脱敏 </Tag
        ><Switch
          v-else-if="column.key === 'status'"
          :checked="record.status === 0"
          :disabled="!can('update', 'rule') || !ruleVersion(record)"
          :loading="pending.has(resourceIdKey(record.id))"
          @change="(checked) => changeStatus(record, !!checked)"
        /><span v-else-if="column.key === 'creatorName'">{{
          record.creatorName || '-'
        }}</span
        ><span v-else-if="column.key === 'createTime'">{{
          categoryTime(record.createTime)
        }}</span>
        <div v-else-if="column.key === 'actions'" class="category-row-actions">
          <Button type="link" @click="detail(record)">
            <i
              class="category-icon"
              :style="{ maskImage: `url(${categoryIcon('detail')})` }"
            ></i
            >详情 </Button
          ><Button
            v-if="can('update', 'rule')"
            type="link"
            @click="edit(record)"
          >
            <i
              class="category-icon"
              :style="{ maskImage: `url(${categoryIcon('edit')})` }"
            ></i
            >修改 </Button
          ><Button
            v-if="can('delete', 'rule')"
            type="link"
            danger
            :disabled="record.status === 0"
            @click="remove(record)"
          >
            <i
              class="category-icon"
              :style="{ maskImage: `url(${categoryIcon('delete')})` }"
            ></i
            >删除
          </Button>
        </div>
      </template> </SafetyWorkbench
    ><RuleForm
      v-if="editing"
      :key="resourceIdKey(editId) || 'create'"
      :id="editId"
      @close="editing = false"
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
