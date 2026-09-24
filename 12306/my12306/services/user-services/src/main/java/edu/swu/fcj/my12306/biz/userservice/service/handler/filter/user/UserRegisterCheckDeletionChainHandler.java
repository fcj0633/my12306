package edu.swu.fcj.my12306.biz.userservice.service.handler.filter.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.swu.fcj.my12306.biz.userservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserDeletionDO;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserDeletionMapper;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserRegisterReqDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 注册责任链 3/3：证件号黑名单——同一证件注销 >= 5 次禁止注册
 */
@Component
@RequiredArgsConstructor
public final class UserRegisterCheckDeletionChainHandler implements UserRegisterCreateChainFilter<UserRegisterReqDTO> {

    private final UserDeletionMapper userDeletionMapper;

    @Override
    public void handler(UserRegisterReqDTO requestParam) {
        Long count = userDeletionMapper.selectCount(Wrappers.lambdaQuery(UserDeletionDO.class)
                .eq(UserDeletionDO::getIdType, requestParam.getIdType())
                .eq(UserDeletionDO::getIdCard, requestParam.getIdCard()));
        if (count >= 5) {
            throw new ServiceException("证件号多次注销账号已被加入黑名单");
        }
    }

    @Override
    public int order() {
        return 2;
    }
}
