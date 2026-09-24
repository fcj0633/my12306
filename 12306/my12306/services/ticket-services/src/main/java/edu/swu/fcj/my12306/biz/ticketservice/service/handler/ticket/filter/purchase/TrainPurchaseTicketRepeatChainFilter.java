package edu.swu.fcj.my12306.biz.ticketservice.service.handler.ticket.filter.purchase;

import edu.swu.fcj.my12306.biz.ticketservice.dto.req.PurchaseTicketReqDTO;
import org.springframework.stereotype.Component;

/**
 * 购票流程过滤器之四：重复购票校验占位。
 * <p>
 * 参考实现亦为 TODO；重复购票规则（同一乘车人同日同车次/同区间）尚属【待确认 #7】，本期不拦截。
 */
@Component
public class TrainPurchaseTicketRepeatChainFilter implements TrainPurchaseTicketChainFilter {

    @Override
    public void handler(PurchaseTicketReqDTO requestParam) {
        // 【待确认 #7】重复购买校验规则未定，保留占位不实现
    }

    @Override
    public int order() {
        return 30;
    }
}
