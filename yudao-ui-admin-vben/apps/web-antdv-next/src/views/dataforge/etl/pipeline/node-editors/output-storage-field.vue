<script lang="ts" setup>
import { Input } from 'antdv-next';

const value = defineModel<string>();
</script>

<template>
  <div class="form-item">
    <label class="form-item__label form-item__label--required">逻辑输出存储</label>
    <Input v-model:value="value" allow-clear placeholder="etl://exports" />
    <div class="form-item__tip">
      填写管理员配置的存储标识，例如 etl://exports。文件按执行人和运行隔离保存。
      旧服务器绝对路径需改为逻辑存储后重新发布。
    </div>
  </div>
</template>
