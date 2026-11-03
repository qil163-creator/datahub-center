<script lang="ts" setup>
import { onMounted, ref } from 'vue';

/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { IconifyIcon } from '@vben/icons';

import { Button, InputNumber, message, Modal, TextArea } from 'antdv-next';

import { compileEtlJava } from '#/api/dataforge/etl';

import './editor-style.css';

defineOptions({ name: 'EtlNodeJavaScript' });

const props = defineProps<{
  config: Record<string, any>;
}>();

const DEFAULT_CODE = `import java.util.*;
import cn.iocoder.yudao.framework.etl.core.script.EtlJavaScript;

public class EtlScript implements EtlJavaScript {

    /**
     * 按批处理数据行（可增删改行与字段），返回值作为节点输出
     * 可使用 Java 基础类库、hutool 等运行时依赖
     */
    @Override
    public List<Map<String, Object>> execute(List<Map<String, Object>> rows) throws Exception {
        for (Map<String, Object> row : rows) {
            // TODO 在此处理数据，例如：row.put("new_field", "value");
        }
        return rows;
    }
}
`;

const compiling = ref(false);

onMounted(() => {
  if (!props.config.code) {
    props.config.code = DEFAULT_CODE;
  }
  if (props.config.batchSize === null || props.config.batchSize === undefined) {
    props.config.batchSize = 1000;
  }
});

async function handleCompile() {
  if (!props.config.code?.trim()) {
    message.warning('请先填写脚本代码');
    return;
  }
  compiling.value = true;
  try {
    const errors = await compileEtlJava(props.config.code);
    if (!errors || errors.length === 0) {
      message.success('编译通过');
    } else {
      Modal.error({
        title: '编译错误',
        width: 720,
        content: errors.join('\n'),
      });
    }
  } finally {
    compiling.value = false;
  }
}
</script>

<template>
  <div class="etl-editor">
    <div class="form-item">
      <div class="flex items-center justify-between">
        <label class="form-item__label form-item__label--required">
          脚本代码（需实现 EtlJavaScript 接口）
        </label>
        <Button
          :loading="compiling"
          size="small"
          type="primary"
          @click="handleCompile"
        >
          <template #icon><IconifyIcon icon="lucide:file-check-2" /></template>
          编译检查
        </Button>
      </div>
      <TextArea
        v-model:value="config.code"
        :rows="18"
        class="java-script__code"
        spellcheck="false"
      />
      <div class="form-item__tip">
        按批调用 execute(rows)，返回值作为节点输出；需要服务端以 JDK 运行（非
        JRE）
      </div>
    </div>
    <div class="form-item w-52">
      <label class="form-item__label">批次行数</label>
      <InputNumber v-model:value="config.batchSize" :min="1" class="w-full" />
    </div>
  </div>
</template>

<style scoped>
.java-script__code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
}
</style>
