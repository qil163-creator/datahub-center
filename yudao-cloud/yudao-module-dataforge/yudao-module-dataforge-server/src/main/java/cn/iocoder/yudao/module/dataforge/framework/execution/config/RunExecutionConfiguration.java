package cn.iocoder.yudao.module.dataforge.framework.execution.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunExecutionProperties.class)
public class RunExecutionConfiguration {

    /** 不作为全局默认调度器，不能改变其他业务的续租资源选择。 */
    @Bean(name = "runMetricsScheduler", defaultCandidate = false)
    public ThreadPoolTaskScheduler runMetricsScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("run-metrics-clock-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * 注册线程池任务执行器组件。
     *
     * @return 线程池任务执行器
     */
    @Bean(name = "runMetricsWorker", defaultCandidate = false)
    public ThreadPoolTaskExecutor runMetricsWorker() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("run-metrics-read-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(1);
        return executor;
    }
}
