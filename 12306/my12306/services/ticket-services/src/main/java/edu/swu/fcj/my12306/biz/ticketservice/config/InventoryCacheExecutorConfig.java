package edu.swu.fcj.my12306.biz.ticketservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class InventoryCacheExecutorConfig {
    @Bean("inventoryCacheExecutor")
    public ThreadPoolTaskExecutor inventoryCacheExecutor() {
        var executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("inventory-cache-");
        return executor;
    }
}
