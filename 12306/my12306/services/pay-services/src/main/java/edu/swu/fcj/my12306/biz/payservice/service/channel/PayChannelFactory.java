package edu.swu.fcj.my12306.biz.payservice.service.channel;

import cn.hutool.core.util.StrUtil;
import edu.swu.fcj.my12306.biz.payservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.payservice.common.enums.PayChannelEnum;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付渠道工厂：按渠道名取出对应实现。
 * <p>
 * 用 Spring 注入的 List 自动收集所有实现，新增渠道无需改动本类 —— 这就是"隔离变化点"的价值。
 */
@Component
public class PayChannelFactory {

    private final Map<String, PayChannelHandler> handlerMap = new HashMap<>();

    public PayChannelFactory(List<PayChannelHandler> handlers) {
        for (PayChannelHandler each : handlers) {
            handlerMap.put(each.channel().getName(), each);
        }
    }

    public PayChannelHandler getHandler(String channel) {
        String channelName = StrUtil.isBlank(channel) ? PayChannelEnum.MOCK_PAY.getName() : channel;
        PayChannelHandler handler = handlerMap.get(channelName);
        if (handler == null) {
            throw new ServiceException("暂不支持该支付渠道");
        }
        return handler;
    }
}
