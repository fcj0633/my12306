package edu.swu.fcj.my12306.biz.payservice.service;

import edu.swu.fcj.my12306.biz.payservice.dto.req.PayCallbackReqDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.req.PayCreateReqDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.resp.PayInfoRespDTO;
import edu.swu.fcj.my12306.biz.payservice.dto.resp.PayRespDTO;

public interface PayService {

    /**
     * 创建（或复用）支付单，返回收银台地址
     */
    PayRespDTO createPay(PayCreateReqDTO requestParam);

    /**
     * 按订单号查询支付单
     */
    PayInfoRespDTO getPayInfoByOrderSn(String orderSn);

    /**
     * 按支付流水号查询支付单
     */
    PayInfoRespDTO getPayInfoByPaySn(String paySn);

    /**
     * 支付结果回调：推进支付单为支付成功，并通知订单与票务
     *
     * @return 下游是否全部通知成功
     */
    boolean payCallback(PayCallbackReqDTO requestParam);

    /**
     * 关闭支付单（订单取消/超时关单时调用），幂等
     */
    boolean closePayByOrderSn(String orderSn);

    /**
     * 把一笔"支付成功但通知未完成"的支付单重新通知下游，供补偿任务与回调复用
     */
    boolean notifyPayResult(String paySn);
}
