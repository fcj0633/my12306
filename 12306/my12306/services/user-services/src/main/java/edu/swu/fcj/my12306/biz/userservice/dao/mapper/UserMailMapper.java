package edu.swu.fcj.my12306.biz.userservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserMailDO;

public interface UserMailMapper extends BaseMapper<UserMailDO> {

    /**
     * 注销用户邮箱映射（置位注销时间戳与删除标识）
     */
    void deletionUser(UserMailDO userMailDO);
}
