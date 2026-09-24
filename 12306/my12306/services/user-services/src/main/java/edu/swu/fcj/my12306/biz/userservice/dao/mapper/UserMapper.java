package edu.swu.fcj.my12306.biz.userservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserDO;

public interface UserMapper extends BaseMapper<UserDO> {

    /**
     * 注销用户（置位注销时间戳与删除标识）
     */
    void deletionUser(UserDO userDO);
}
