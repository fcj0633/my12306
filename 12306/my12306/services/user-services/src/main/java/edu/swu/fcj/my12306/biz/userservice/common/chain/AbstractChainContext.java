package edu.swu.fcj.my12306.biz.userservice.common.chain;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 责任链容器：启动后按 mark 收集所有 Handler 并按 order 排序；
 * 业务侧调用 handler(mark, param) 顺序执行。
 * <p>
 * 用 CommandLineRunner + ApplicationContext 收集（而非构造注入），
 * 避免 Handler 反向依赖 Service 造成的构造循环。
 */
@Component
public class AbstractChainContext implements ApplicationContextAware, CommandLineRunner {

    private ApplicationContext applicationContext;

    private final Map<String, List<AbstractChainHandler>> container = new HashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(String... args) {
        Map<String, AbstractChainHandler> beans = applicationContext.getBeansOfType(AbstractChainHandler.class);
        beans.forEach((name, handler) -> container
                .computeIfAbsent(handler.mark(), k -> new ArrayList<>())
                .add(handler));
        container.values().forEach(list -> list.sort(Comparator.comparingInt(AbstractChainHandler::order)));
    }

    /**
     * 按 mark 取链顺序执行；任一节点抛异常即中断
     */
    public void handler(String mark, Object requestParam) {
        List<AbstractChainHandler> handlers = container.get(mark);
        if (handlers != null) {
            handlers.forEach(handler -> handler.handler(requestParam));
        }
    }
}
