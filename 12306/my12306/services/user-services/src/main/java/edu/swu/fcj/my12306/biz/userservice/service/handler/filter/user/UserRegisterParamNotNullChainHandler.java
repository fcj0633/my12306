package edu.swu.fcj.my12306.biz.userservice.service.handler.filter.user;

import edu.swu.fcj.my12306.biz.userservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserRegisterReqDTO;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 注册责任链 1/3：参数非空校验（order=0，最先执行）
 */
@Component
public final class UserRegisterParamNotNullChainHandler implements UserRegisterCreateChainFilter<UserRegisterReqDTO> {

    @Override
    public void handler(UserRegisterReqDTO requestParam) {
        if (Objects.isNull(requestParam.getUsername())) {
            throw new ServiceException("用户名不能为空");
        }
        if (Objects.isNull(requestParam.getPassword())) {
            throw new ServiceException("密码不能为空");
        }
        if (Objects.isNull(requestParam.getRealName())) {
            throw new ServiceException("真实姓名不能为空");
        }
        if (Objects.isNull(requestParam.getIdType())) {
            throw new ServiceException("证件类型不能为空");
        }
        if (Objects.isNull(requestParam.getIdCard())) {
            throw new ServiceException("证件号码不能为空");
        }
        if (Objects.isNull(requestParam.getPhone())) {
            throw new ServiceException("手机号不能为空");
        }
        if (Objects.isNull(requestParam.getMail())) {
            throw new ServiceException("邮箱不能为空");
        }
    }

    @Override
    public int order() {
        return 0;
    }
}
