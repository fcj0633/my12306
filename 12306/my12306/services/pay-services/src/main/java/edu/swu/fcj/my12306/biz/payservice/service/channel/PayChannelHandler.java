package edu.swu.fcj.my12306.biz.payservice.service.channel;

import edu.swu.fcj.my12306.biz.payservice.common.enums.PayChannelEnum;
import edu.swu.fcj.my12306.biz.payservice.dao.entity.PayDO;

/**
 * 支付渠道抽象：不同渠道的"怎么发起支付、回调怎么解析"各不相同，把它们隔离在实现类里。
 * <p>
 * 本期只有一个模拟渠道实现，但接口先立起来 —— 未来接支付宝/微信时只需要新增实现类，不改业务流程。
 */
public interface PayChannelHandler {

    /**
     * 该实现负责的渠道
     */
    PayChannelEnum channel();

    /**
     * 生成收银台地址：用户拿到它就能去"付钱"
     */
    String buildCashierUrl(PayDO payDO);
}
