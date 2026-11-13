<script lang="ts" setup>
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { confirm, Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  message,
  Modal,
  Spin,
  TextArea,
} from 'antdv-next';

import {
  createIndicatorBoard,
  deleteIndicatorBoard,
  getIndicatorBoard,
  getIndicatorBoardList,
  updateIndicatorBoard,
} from '#/api/modeling/indicator-model';
import { createLatestWins } from '#/utils/resource-id';

import { BOARD_MAX_WIDGETS, nextWidgetKey } from './board';
import WidgetEditor from './widget-editor.vue';
import WidgetView from './widget-view.vue';

/** 指标看板：组件只保存查询定义，展示时按查看者实时查询并动态脱敏。 */
defineOptions({ name: 'ModelingIndicatorBoard' });

const { hasAccessByCodes } = useAccess();
const canQuery = computed(() =>
  hasAccessByCodes(['modeling:indicator-board:query']),
);
const canCreate = computed(() =>
  hasAccessByCodes(['modeling:indicator-board:create']),
);
const canUpdate = computed(() =>
  hasAccessByCodes(['modeling:indicator-board:update']),
);
const canDelete = computed(() =>
  hasAccessByCodes(['modeling:indicator-board:delete']),
);
const canRun = computed(() =>
  hasAccessByCodes(['modeling:indicator-model:data-query']),
);

const boards = ref<IndicatorModelApi.Board[]>([]);
const keyword = ref('');
const selected = ref<IndicatorModelApi.Board>();
const loading = ref(false);
const listGuard = createLatestWins();
const detailGuard = createLatestWins();

async function loadList() {
  if (!canQuery.value) return;
  const ticket = listGuard.next();
  loading.value = true;
  try {
    const list = await getIndicatorBoardList(keyword.value.trim() || undefined);
    if (ticket.isCurrent()) boards.value = list ?? [];
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

async function select(id: IndicatorModelApi.Id) {
  const ticket = detailGuard.next();
  const board = await getIndicatorBoard(id);
  if (ticket.isCurrent()) selected.value = board;
}

// ---------- 编辑 ----------
const editing = ref(false);
const saving = ref(false);
const draft = ref<IndicatorModelApi.BoardSave>({ name: '', widgets: [] });
const widgetOpen = ref(false);
const widgetIndex = ref<number>();

function startCreate() {
  draft.value = { name: '', description: '', widgets: [] };
  editing.value = true;
}

function startEdit() {
  const board = selected.value;
  if (!board) return;
  draft.value = {
    id: board.id,
    name: board.name,
    description: board.description,
    widgets: structuredClone(board.widgets ?? []),
    expectedRevision: board.revision,
  };
  editing.value = true;
}

function openWidget(index?: number) {
  if (index === undefined && draft.value.widgets.length >= BOARD_MAX_WIDGETS) {
    message.warning(`组件最多 ${BOARD_MAX_WIDGETS} 个`);
    return;
  }
  widgetIndex.value = index;
  widgetOpen.value = true;
}

function saveWidget(widget: IndicatorModelApi.BoardWidget) {
  if (widgetIndex.value === undefined) {
    draft.value.widgets.push({
      ...widget,
      key: nextWidgetKey(draft.value.widgets),
    });
  } else {
    draft.value.widgets.splice(widgetIndex.value, 1, {
      ...widget,
      key: draft.value.widgets[widgetIndex.value]!.key,
    });
  }
}

async function saveBoard() {
  if (saving.value) return;
  if (!draft.value.name.trim()) {
    message.warning('请填写看板名称');
    return;
  }
  if (draft.value.widgets.length === 0) {
    message.warning('请至少添加一个组件');
    return;
  }
  saving.value = true;
  try {
    const payload = { ...draft.value, name: draft.value.name.trim() };
    let id = payload.id;
    if (id === undefined) {
      id = await createIndicatorBoard(payload);
    } else {
      await updateIndicatorBoard(payload);
    }
    message.success('已保存');
    editing.value = false;
    await loadList();
    if (id !== undefined) await select(id);
  } finally {
    saving.value = false;
  }
}

function removeBoard() {
  const board = selected.value;
  if (!board) return;
  void confirm(`确定删除看板「${board.name}」？`).then(async () => {
    await deleteIndicatorBoard(board.id);
    message.success('已删除');
    selected.value = undefined;
    await loadList();
  });
}

onMounted(loadList);
onBeforeUnmount(() => {
  listGuard.invalidate();
  detailGuard.invalidate();
});
</script>

<template>
  <Page auto-content-height>
    <Alert
      v-if="!canQuery"
      type="warning"
      show-icon
      message="当前账号没有指标看板查询权限"
    />
    <div v-else class="flex h-full gap-4">
      <Card class="w-72 shrink-0" size="small" title="看板">
        <template #extra>
          <Button
            v-if="canCreate"
            size="small"
            type="primary"
            @click="startCreate"
            >
新建
</Button>
        </template>
        <Input
          v-model:value="keyword"
          allow-clear
          class="mb-2"
          placeholder="搜索看板"
          @press-enter="loadList"
        />
        <Spin :spinning="loading">
          <div
            v-for="board in boards"
            :key="String(board.id)"
            class="hover:bg-accent cursor-pointer rounded px-2 py-2"
            :class="{
              'bg-accent': selected && String(selected.id) === String(board.id),
            }"
            @click="select(board.id)"
          >
            <div class="font-medium">{{ board.name }}</div>
            <div class="text-muted-foreground text-xs">
              {{ board.widgetCount }} 个组件
            </div>
          </div>
          <Empty v-if="!boards.length" description="暂无看板" />
        </Spin>
      </Card>
      <div class="min-w-0 flex-1">
        <Alert
          v-if="!canRun"
          class="mb-3"
          type="warning"
          show-icon
          message="当前账号没有指标查询权限，看板组件无法出数"
        />
        <Card v-if="selected" size="small" :title="selected.name">
          <template #extra>
            <div class="flex gap-2">
              <Button
                v-if="selected.editable && canUpdate"
                size="small"
                @click="startEdit"
                >
编辑
</Button>
              <Button
                v-if="selected.editable && canDelete"
                danger
                size="small"
                @click="removeBoard"
                >
删除
</Button>
            </div>
          </template>
          <div
            v-if="selected.description"
            class="text-muted-foreground mb-3 text-sm"
          >
            {{ selected.description }}
          </div>
          <div v-if="canRun" class="grid grid-cols-1 gap-3 xl:grid-cols-2">
            <WidgetView
              v-for="widget in selected.widgets ?? []"
              :key="widget.key"
              :widget="widget"
            />
          </div>
        </Card>
        <Empty v-else description="选择左侧看板查看" />
      </div>
    </div>

    <Modal
      v-model:open="editing"
      :confirm-loading="saving"
      :title="draft.id === undefined ? '新建看板' : '编辑看板'"
      width="720px"
      @ok="saveBoard"
    >
      <div class="flex flex-col gap-3">
        <Input
          v-model:value="draft.name"
          :maxlength="100"
          placeholder="看板名称"
        />
        <TextArea
          v-model:value="draft.description"
          :maxlength="500"
          :rows="2"
          placeholder="说明（选填）"
        />
        <div class="flex items-center justify-between">
          <span class="font-medium">组件（{{ draft.widgets.length }}/{{ BOARD_MAX_WIDGETS }}）</span>
          <Button size="small" @click="openWidget()">添加组件</Button>
        </div>
        <div
          v-for="(widget, index) in draft.widgets"
          :key="widget.key"
          class="flex items-center justify-between rounded border px-3 py-2"
        >
          <span>{{ widget.title }}（{{ widget.chartType }}，{{
              widget.query.indicatorIds.length
            }}
            个指标）</span>
          <span class="flex gap-1">
            <Button size="small" type="link" @click="openWidget(index)">编辑</Button>
            <Button
              danger
              size="small"
              type="link"
              @click="draft.widgets.splice(index, 1)"
              >删除</Button>
          </span>
        </div>
      </div>
    </Modal>
    <WidgetEditor
      v-model:open="widgetOpen"
      :widget="
        widgetIndex === undefined ? undefined : draft.widgets[widgetIndex]
      "
      @save="saveWidget"
    />
  </Page>
</template>
