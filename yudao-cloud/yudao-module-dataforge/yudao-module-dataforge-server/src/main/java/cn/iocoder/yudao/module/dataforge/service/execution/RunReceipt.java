package cn.iocoder.yudao.module.dataforge.service.execution;

/** 已持久受理的运行回执；stopRequested 不等于执行已退出。 */
public record RunReceipt(Long recordId, boolean replayed, Integer runStatus, boolean stopRequested) {

}
