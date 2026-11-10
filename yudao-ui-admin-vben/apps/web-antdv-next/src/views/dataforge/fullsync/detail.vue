<script setup lang="ts">
import type {
  DatabaseOption,
  FullSyncTableProperties,
  ResponsibleUser,
  Wizard,
} from '#/api/dataforge/fullsync';

import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Button,
  ConfigProvider,
  Input,
  Modal,
  Spin,
  Table,
  theme,
} from 'antdv-next';

import {
  getDatabaseOptions,
  getResponsibleUsers,
  getWizard,
  LIST_PATH,
} from '#/api/dataforge/fullsync';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useFullSyncScope } from './use-scope';

import '#/components/detail-fill-table.css';
import './fullsync.css';

defineOptions({ name: 'DataforgeFullSyncDetail' });
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const id = computed(() =>
  typeof route.params.id === 'string' && /^\d+$/.test(route.params.id)
    ? route.params.id
    : '',
);
const task = ref<Wizard>();
const databases = ref<DatabaseOption[]>([]);
const users = ref<ResponsibleUser[]>([]);
const creatorName = computed(
  () =>
    users.value.find((user) => String(user.id) === task.value?.creator)
      ?.nickname ||
    task.value?.creator ||
    '-',
);
const tab = ref('mappings');
const loading = ref(false);
const failed = ref(false);
const configOpen = ref(false);
const selectedTable = ref('');
const sqlOpen = ref(false);
const sqlTitle = ref('');
const sqlValue = ref('');
const { active, scope } = useFullSyncScope(() => id.value);
const request = useObjectRequest(() => scope.value);
const allowed = computed(() => hasAccessByCodes(['dataforge:full-sync:query']));
const config = computed(() => task.value?.config);
const properties = computed(
  () => config.value?.tableProperties?.[selectedTable.value] ?? {},
);
const tabs = [
  { key: 'mappings', label: '多表映射' },
  { key: 'columns', label: '追加字段' },
  { key: 'info', label: '基本信息' },
];
const mappingColumns = [
  { key: 'index', title: '编号', width: 50 },
  { key: 'source', title: '来源物理表名称', width: 240 },
  { key: 'sourceComment', title: '来源中文表名称', width: 240 },
  { key: 'targetTable', title: '目标物理表名称', width: 240 },
  { key: 'targetComment', title: '目标中文表名称', width: 240 },
  { key: 'partitionRule', title: '分区规则', width: 120 },
  { key: 'bucketRule', title: '分桶规则', width: 120 },
  { key: 'replicaCount', title: '副本数量', width: 120 },
  { key: 'actions', title: '操作', fixed: 'right' as const, width: 120 },
];
const columnColumns = [
  { title: '编号', key: 'index', width: 70 },
  { title: '字段名称', dataIndex: 'name' },
  { title: '字段描述', dataIndex: 'description' },
  { title: '数据类型', dataIndex: 'type' },
  { title: '属性长度', dataIndex: 'length' },
  { title: '小数位', dataIndex: 'scale' },
  { title: '默认值', dataIndex: 'defaultValue' },
];
const mappings = computed(
  () =>
    config.value?.sourceSelectedTables.map((source, index) => ({
      source,
      index: index + 1,
      ...config.value?.tableProperties?.[source],
      targetTable:
        config.value?.configMap?.[source]?.targetTableName ||
        config.value?.tableProperties?.[source]?.targetTable ||
        source,
      targetComment:
        config.value?.configMap?.[source]?.targetTableComment ??
        config.value?.tableProperties?.[source]?.targetComment,
    })) || [],
);
const db = (databaseId: unknown) =>
  databases.value.find((entry) => String(entry.id) === String(databaseId));
const format = (value?: number) =>
  value ? formatDate(value, 'YYYY-MM-DD HH:mm') : '-';
const basicInfo = computed(() => {
  const c = config.value;
  if (!c) return [];
  const priority: Record<string, string> = {
    HIGHEST: '最高',
    HIGH: '高',
    MEDIUM: '中',
    LOW: '低',
    LOWEST: '最低',
  };
  const dirty: Record<string, string> = {
    IGNORE: '忽略',
    STOP: '暂停任务',
    CONTINUE: '继续执行',
  };
  return [
    ['责任人', c.responsibleName],
    ['责任人电话', c.responsiblePhone],
    ['源库类型', db(c.sourceDatabaseId)?.type],
    ['目标库类型', db(c.targetDatabaseId)?.type],
    ['执行方式', c.executionMethod === 'CRON' ? 'Cron表单式' : '手动执行'],
    ['调度周期', c.cron],
    [
      '错误处理策略',
      c.errorContinue === false ? '错误暂停任务' : '错误继续执行',
    ],
    ['脏数据处理策略', dirty[c.dirtyDataStrategy ?? 'IGNORE']],
    ['任务优先级', priority[c.taskPriority ?? 'MEDIUM']],
    ['Worker分组', c.workerGroup],
    ['失败重试次数', c.retryTimes],
    ['失败重试间隔', c.retryInterval],
    ['延迟执行时间', c.delayTime],
    ['执行引擎', c.engine],
    ['Driver核心数', c.driverCores],
    ['Driver内存数', c.driverMemory],
    ['Executor数量', c.executorCount],
    ['Executor内存数', c.executorMemory],
    ['Executor核心数', c.executorCores],
    ['Yarn队列', c.yarnQueue],
  ];
});
async function load() {
  if (!active.value || !id.value || !allowed.value) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const result = await getWizard(id.value);
    if (current()) task.value = result;
    const [options, userResult] = await Promise.allSettled([
      getDatabaseOptions(),
      getResponsibleUsers(),
    ]);
    if (!current()) return;
    if (options.status === 'fulfilled') databases.value = options.value;
    if (userResult.status === 'fulfilled') users.value = userResult.value;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function recordValue(record: object, key: unknown) {
  return (record as Record<string, unknown>)[String(key)] || '-';
}
function showMapping(source: string) {
  selectedTable.value = source;
  configOpen.value = true;
}
function showSql(property: keyof FullSyncTableProperties, title: string) {
  sqlTitle.value = title;
  sqlValue.value = String(properties.value[property] || '');
  sqlOpen.value = true;
}
watch(
  scope,
  () => {
    task.value = undefined;
    databases.value = [];
    users.value = [];
    configOpen.value = false;
    sqlOpen.value = false;
    failed.value = false;
    loading.value = false;
    void load();
  },
  { immediate: true },
);
</script>
<template>
  <ConfigProvider
    :theme="{
      algorithm: theme.defaultAlgorithm,
      inherit: false,
      token: { colorPrimary: '#2663ff', borderRadius: 2 },
    }"
  >
    <Page auto-content-height content-class="qdata-fullsync">
      <div class="fs-page">
        <Alert
          v-if="!id || !allowed"
          type="warning"
          title="任务编号无效或没有查询权限"
          show-icon
        />

        <Spin :spinning="loading">
          <section v-if="task && config" class="fs-panel fs-detail-header">
            <div class="fs-detail-heading">
              <span class="fs-id">{{ task.id }}</span><strong>{{ config.taskName }}</strong><span class="fs-tag blue">{{
                config.taskStatus === 1 ? '已上线' : '未上线'
              }}</span><DetailBackButton @click="router.push(LIST_PATH)" />
            </div>
            <div class="fs-description-grid">
              <div class="fs-description-label">源数据库</div>
              <div>
                {{
                  db(config.sourceDatabaseId)?.databaseName ||
                  db(config.sourceDatabaseId)?.name ||
                  config.sourceDatabaseId
                }}
              </div>
              <div class="fs-description-label">目标数据库</div>
              <div>
                {{
                  db(config.targetDatabaseId)?.databaseName ||
                  db(config.targetDatabaseId)?.name ||
                  config.targetDatabaseId
                }}
              </div>
              <div class="fs-description-label">同步表数量</div>
              <div>{{ config.sourceSelectedTables.length }}</div>
              <div class="fs-description-label">描述</div>
              <div class="fs-description-wide">
                {{ config.description || '-' }}
              </div>
              <div class="fs-description-label">创建人</div>
              <div>{{ creatorName }}</div>
              <div class="fs-description-label">创建时间</div>
              <div>{{ format(task.createTime) }}</div>
              <div class="fs-description-label">更新时间</div>
              <div>{{ format(task.updateTime) }}</div>
            </div>
          </section>
        </Spin>
        <section v-if="config" class="fs-panel fs-detail-main">
          <nav class="fs-tabs" aria-label="整库同步详情">
            <button
              v-for="item in tabs"
              :key="item.key"
              :class="{ active: tab === item.key }"
              :aria-current="tab === item.key ? 'page' : undefined"
              @click="tab = item.key"
            >
              {{ item.label }}
            </button>
          </nav>
          <Table
            v-if="tab === 'mappings'"
            class="fs-bordered-table detail-fill-table"
            :columns="mappingColumns"
            :data-source="mappings"
            row-key="source"
            :pagination="false"
            :scroll="{ x: 'max-content' }"
            bordered
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'actions'">
                <button class="fs-link" @click="showMapping(record.source)">
                  配置详情
                </button>
              </template>
              <template v-else-if="column.key === 'partitionRule'">
                {{ record.partitionRule ? '自定义分区' : '不分区' }}
              </template>
              <template v-else-if="column.key === 'bucketRule'">
                {{ record.bucketRule ? '自定义分桶' : '不分桶' }}
              </template>
              <template v-else-if="column.key === 'replicaCount'">
                {{ record.replicaCount ?? 1 }}
              </template>
              <template v-else>{{ recordValue(record, column.key) }}</template>
            </template>
          </Table>
          <Table
            v-else-if="tab === 'columns'"
            class="fs-bordered-table detail-fill-table"
            :columns="columnColumns"
            :data-source="config.appendColumns || []"
            :row-key="(_row, index) => String(index)"
            :pagination="false"
            :scroll="{ x: 800 }"
            bordered
          >
            <template #bodyCell="{ column, index }">
              <template v-if="column.key === 'index'">
                {{ index + 1 }}
              </template>
</template><template #emptyText>
              <div class="fs-empty">暂无记录</div>
            </template>
          </Table>
          <div v-else class="fs-info-grid">
            <template v-for="(entry, index) in basicInfo" :key="index">
              <div class="fs-description-label">{{ entry[0] }}</div>
              <div>{{ entry[1] ?? '-' }}</div>
            </template>
          </div>
        </section>
      </div>
      <Modal
        v-model:open="configOpen"
        title="配置详情"
        :width="800"
        :footer="null"
        destroy-on-close
      >
        <div class="qdata-fullsync fs-config-detail">
          <label><span>来源物理表名称</span><Input :value="selectedTable" disabled /></label><label><span>来源中文表名称</span><Input
              :value="String(properties.sourceComment || '')"
              disabled
/></label><label><span>目标物理表名称</span><Input
              :value="
                config?.configMap?.[selectedTable]?.targetTableName ||
                properties.targetTable ||
                selectedTable
              "
              disabled
/></label><label><span>目标中文表名称</span><Input
              :value="
                config?.configMap?.[selectedTable]?.targetTableComment ??
                properties.targetComment
              "
              disabled
/></label><label><span>分区规则</span><Button
              class="fs-light-button"
              @click="showSql('partitionRule', '分区规则')"
              >查看分区规则</Button></label><label><span>分桶规则</span><Button
              class="fs-light-button"
              @click="showSql('bucketRule', '分桶规则')"
              >查看分桶规则</Button></label>
          <div class="fs-modal-footer">
            <Button @click="configOpen = false">关 闭</Button>
          </div>
        </div>
      </Modal>
      <Modal
        v-model:open="sqlOpen"
        :title="sqlTitle"
        :width="800"
        :footer="null"
      >
        <pre class="fs-sql-preview">{{ sqlValue || '未配置' }}</pre>
        <div class="fs-modal-footer">
          <Button @click="sqlOpen = false">关 闭</Button>
        </div>
      </Modal>
    </Page>
  </ConfigProvider>
</template>

<style scoped>
.fs-page {
  gap: 16px;
}

.fs-detail-header,
.fs-detail-main {
  padding: 20px;
  background: hsl(var(--card));
  border-radius: var(--radius);
}

.fs-detail-main {
  display: flex;
  flex-direction: column;
  padding-top: 8px;
}

.fs-detail-heading {
  margin-bottom: 20px;
}

.fs-tabs {
  margin-bottom: 20px;
}

.fs-page .fs-description-grid,
.fs-page .fs-info-grid {
  gap: 1px;
  overflow: hidden;
  background: hsl(var(--border));
  border: 1px solid hsl(var(--border));
  border-radius: var(--radius);
}

.fs-page .fs-description-grid > div,
.fs-page .fs-info-grid > div {
  min-width: 0;
  color: hsl(var(--foreground));
  background: hsl(var(--card));
  border: 0;
}

.fs-page .fs-description-grid > .fs-description-label,
.fs-page .fs-info-grid > .fs-description-label {
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted) / 45%);
}

@media (width <= 640px) {
  .fs-detail-header {
    padding: 16px;
  }

  .fs-detail-main {
    padding: 8px 16px 16px;
  }
}
</style>
