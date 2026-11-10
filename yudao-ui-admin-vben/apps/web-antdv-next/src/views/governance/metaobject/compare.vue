<script setup lang="ts">
import type { GovernanceMetaObjectApi as Api } from '#/api/governance/metaobject';

import {
  computed,
  onActivated,
  onDeactivated,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRoute } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  Descriptions,
  DescriptionsItem,
  Input,
  Select,
  Spin,
  Table,
} from 'antdv-next';

import {
  compareMetaSnapshots,
  getMetaSnapshot,
  getMetaSnapshotList,
} from '#/api/governance/metaobject';
import { useObjectRequest } from '#/hooks/use-object-request';

import { formatTime } from './data';

import '../metadata-common/metadata.css';

defineOptions({ name: 'GovernanceMetaTableCompare' });
const route = useRoute();
const id = computed(() =>
  route.name === 'GovernanceMetaTableCompare'
    ? String(route.params.id ?? '')
    : '',
);
const snapshots = ref<Api.Snapshot[]>([]);
const bodies = ref<
  Partial<Record<'from' | 'to', { columnCount: number; comment: string }>>
>({});
const selection = reactive<{ from?: Api.Id; to?: Api.Id }>({});
const comparison = ref<Api.SnapshotComparison>();
const keyword = ref('');
const loading = ref(false);
const failed = ref(false);
let active = true;
const request = useObjectRequest(
  () => id.value,
  () => {
    snapshots.value = [];
    bodies.value = {};
    comparison.value = undefined;
    loading.value = false;
  },
);
const comparisonRequest = useObjectRequest(
  () => `${id.value}/${selection.from}/${selection.to}`,
  () => {
    comparison.value = undefined;
    bodies.value = {};
    loading.value = false;
  },
);
const options = computed(() =>
  snapshots.value.map((snapshot) => ({
    label: snapshot.structVersion || '-',
    value: snapshot.id,
  })),
);
const sides = computed(() =>
  (['from', 'to'] as const).map((side) => ({
    side,
    title: side === 'from' ? '基准版本' : '对比版本',
    snapshot: snapshots.value.find(
      (snapshot) => String(snapshot.id) === String(selection[side]),
    ),
    body: bodies.value[side],
    rows: (comparison.value?.columnChanges ?? [])
      .filter((change) =>
        change.columnName
          .toLowerCase()
          .includes(keyword.value.trim().toLowerCase()),
      )
      .map((change) => {
        const column = side === 'from' ? change.before : change.after;
        return {
          ...column,
          key: change.columnName,
          changeType: change.changeType,
          columnName: column ? change.columnName : undefined,
        };
      }),
  })),
);
const columns = [
  { title: '字段名称', dataIndex: 'columnName', width: 180 },
  { title: '字段注释', dataIndex: 'comment', width: 225 },
  { title: '数据类型', dataIndex: 'dataType', width: 140 },
  { title: '是否必填', dataIndex: 'nullable', width: 110 },
  { title: '默认值', dataIndex: 'defaultValue', width: 110 },
];
function source(value?: number) {
  return ['-', '采集', '手工维护', '导入', '回滚'][value ?? 0] ?? '-';
}
function describe(snapshot: Api.Snapshot) {
  const body: unknown = JSON.parse(snapshot.snapshot ?? '{}');
  if (
    !body ||
    typeof body !== 'object' ||
    !('table' in body) ||
    !('columns' in body) ||
    !Array.isArray(body.columns) ||
    !body.table ||
    typeof body.table !== 'object'
  )
    throw new Error('快照内容无效');
  const comment =
    'comment' in body.table && typeof body.table.comment === 'string'
      ? body.table.comment
      : '-';
  return { columnCount: body.columns.length, comment };
}
async function compare() {
  if (
    !id.value ||
    !active ||
    selection.from === undefined ||
    selection.to === undefined
  )
    return;
  const current = comparisonRequest.begin();
  loading.value = true;
  failed.value = false;
  try {
    const fromSnapshot = getMetaSnapshot(selection.from);
    const [result, from, to] = await Promise.all([
      compareMetaSnapshots({
        tableId: id.value,
        fromSnapshotId: selection.from,
        toSnapshotId: selection.to,
      }),
      fromSnapshot,
      String(selection.from) === String(selection.to)
        ? fromSnapshot
        : getMetaSnapshot(selection.to),
    ]);
    const selectedBodies = { from: describe(from), to: describe(to) };
    if (current() && active) {
      comparison.value = result;
      bodies.value = selectedBodies;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
async function load() {
  if (!id.value || !active) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getMetaSnapshotList(id.value);
    if (!current()) return;
    snapshots.value = result;
    const requested =
      typeof route.query.snapshotId === 'string'
        ? route.query.snapshotId
        : undefined;
    const index = requested
      ? result.findIndex((snapshot) => String(snapshot.id) === requested)
      : 0;
    const target = result[Math.max(index, 0)];
    selection.to = target?.id;
    selection.from = result[Math.max(index, 0) + 1]?.id ?? target?.id;
    if (!target) {
      loading.value = false;
      return;
    }
    await compare();
  } catch {
    if (current()) {
      failed.value = true;
      loading.value = false;
    }
  }
}
watch(() => [id.value, route.query.snapshotId], load, { immediate: true });
onDeactivated(() => {
  active = false;
  request.begin();
  comparisonRequest.begin();
  loading.value = false;
});
onActivated(() => {
  if (!active) {
    active = true;
    void load();
  }
});
</script>

<template>
  <Page auto-content-height content-class="metadata-page">
    <Spin :spinning="loading" :classes="{ root: 'metadata-compare-spin' }">
      <div class="metadata-comparison">
        <div class="metadata-comparison__headers">
          <section
            v-for="side in sides"
            :key="side.side"
            class="metadata-panel"
          >
            <div class="metadata-comparison__title">
              <h3 class="metadata-section">{{ side.title }}</h3>
              <Select
                v-model:value="selection[side.side]"
                :options="options"
                aria-label="选择对比版本"
                @change="compare"
              />
            </div>
            <Descriptions
              :key="`${selection[side.side]}/${side.body?.comment}/${side.body?.columnCount}`"
              bordered
              size="small"
              :column="2"
            >
              <DescriptionsItem label="采集完成时间">
                {{ formatTime(side.snapshot?.createTime) }}
              </DescriptionsItem>
              <DescriptionsItem label="执行方式">
                <span class="metadata-tag">{{
                  source(side.snapshot?.changeSource)
                }}</span>
              </DescriptionsItem>
              <DescriptionsItem label="元数据类型">
                <span class="metadata-tag">数据库表</span>
              </DescriptionsItem>
              <DescriptionsItem label="字段数">
                {{ side.body?.columnCount ?? '-' }}
              </DescriptionsItem>
              <DescriptionsItem label="表描述" :span="2">
                {{ side.body?.comment ?? '-' }}
              </DescriptionsItem>
            </Descriptions>
          </section>
        </div>
        <section class="metadata-panel metadata-comparison__fields">
          <div class="metadata-comparison__title">
            <div class="metadata-comparison__legend">
              <h3 class="metadata-section">全部字段</h3>
              <span>红色代表删除，绿色代表新增，黄色代表修改</span>
            </div>
            <Input
              v-model:value="keyword"
              allow-clear
              placeholder="请输入字段名称"
            />
          </div>
          <div class="metadata-comparison__tables">
            <Table
              v-for="side in sides"
              :key="side.side"
              class="metadata-comparison__table"
              :columns="columns"
              :data-source="side.rows"
              :pagination="false"
              row-key="key"
              :scroll="{ x: 640 }"
              size="small"
              :row-class-name="
                (record) =>
                  `metadata-comparison__row--${record.changeType.toLowerCase()}`
              "
            >
              <template #bodyCell="{ column, text }">
                <template v-if="column.dataIndex === 'nullable'">
                  <span
                    class="metadata-tag"
                    :class="text === 0 ? '' : 'metadata-tag--error'"
                    >{{ text === 0 ? '是' : text === 1 ? '否' : '-' }}</span
                  > </template
                ><template v-else>{{ text ?? '-' }}</template>
              </template>
            </Table>
          </div>
        </section>
      </div>
    </Spin>
  </Page>
</template>

<style scoped>
.metadata-compare-spin {
  min-height: 0;
}

.metadata-comparison {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.metadata-comparison__headers,
.metadata-comparison__tables {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
}

.metadata-comparison__title {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  min-height: 42px;
  margin-bottom: 12px;
  border-bottom: 1px solid hsl(var(--border));
}

.metadata-comparison__title .metadata-section {
  margin: 0;
}

.metadata-comparison__title .ant-select {
  width: 200px;
}

.metadata-comparison__title .ant-input-affix-wrapper {
  width: 240px;
}

.metadata-comparison__legend {
  display: flex;
  gap: 16px;
  align-items: center;
}

.metadata-comparison__legend > span {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.metadata-comparison__table {
  min-width: 0;
}

.metadata-comparison__table :deep(.metadata-comparison__row--added > td) {
  background: #f0f9eb;
}

.metadata-comparison__table :deep(.metadata-comparison__row--removed > td) {
  background: #fff0f0;
}

.metadata-comparison__table :deep(.metadata-comparison__row--modified > td) {
  background: #fff9e8;
}

@media (max-width: 900px) {
  .metadata-comparison__headers,
  .metadata-comparison__tables {
    grid-template-columns: minmax(0, 1fr);
  }

  .metadata-comparison__title {
    flex-wrap: wrap;
    padding-bottom: 10px;
  }

  .metadata-comparison__legend {
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
