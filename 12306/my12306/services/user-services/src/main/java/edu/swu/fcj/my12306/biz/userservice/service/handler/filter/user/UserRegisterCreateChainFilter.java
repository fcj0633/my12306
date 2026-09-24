package edu.swu.fcj.my12306.biz.userservice.service.handler.filter.user;

import edu.swu.fcj.my12306.biz.userservice.common.chain.AbstractChainHandler;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserRegisterReqDTO;

/**
 * 注册责任链处理器约束：统一 mark 为 USER_REGISTER_FILTER
 */
public interface UserRegisterCreateChainFilter<T extends UserRegisterReqDTO> extends AbstractChainHandler<T> {

    @Override
    default String mark() {
        return "USER_REGISTER_FILTER";
    }
}
