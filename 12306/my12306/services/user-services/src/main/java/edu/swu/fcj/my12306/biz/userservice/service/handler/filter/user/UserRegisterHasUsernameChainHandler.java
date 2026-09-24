package edu.swu.fcj.my12306.biz.userservice.service.handler.filter.user;

import edu.swu.fcj.my12306.biz.userservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserRegisterReqDTO;
import edu.swu.fcj.my12306.biz.userservice.service.UserLoginService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 注册责任链 2/3：用户名可用性校验（布隆过滤器 + 复用集合）
 */
@Component
@RequiredArgsConstructor
public final class UserRegisterHasUsernameChainHandler implements UserRegisterCreateChainFilter<UserRegisterReqDTO> {

    private final UserLoginService userLoginService;

    @Override
    public void handler(UserRegisterReqDTO requestParam) {
        if (!userLoginService.hasUsername(requestParam.getUsername())) {
            throw new ServiceException("用户名已存在");
        }
    }

    @Override
    public int order() {
        return 1;
    }
}
