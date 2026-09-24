package edu.swu.fcj.my12306.biz.payservice.service.channel;

import edu.swu.fcj.my12306.biz.payservice.common.enums.PayChannelEnum;
import edu.swu.fcj.my12306.biz.payservice.dao.entity.PayDO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模拟支付渠道：不连接任何外部系统。
 * <p>
 * 它返回一个指向本服务"模拟收银台"的地址，用户访问该地址就等于
 * "在第三方收银台点了确认支付"，随后触发支付结果回调 —— 这样整条支付链路在本地可完整验证。
 */
@Component
public class MockPayChannelHandler implements PayChannelHandler {

    @Value("${my12306.pay.mock-cashier-url:http://127.0.0.1:9004/api/pay-service/pay/mock-cashier}")
    private String mockCashierUrl;

    @Override
    public PayChannelEnum channel() {
        return PayChannelEnum.MOCK_PAY;
    }

    @Override
    public String buildCashierUrl(PayDO payDO) {
        return mockCashierUrl + "?paySn=" + payDO.getPaySn();
    }
}
