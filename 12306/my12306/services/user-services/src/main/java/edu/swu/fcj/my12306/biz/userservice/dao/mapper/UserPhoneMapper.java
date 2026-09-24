package edu.swu.fcj.my12306.biz.userservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserPhoneDO;

public interface UserPhoneMapper extends BaseMapper<UserPhoneDO> {

    /**
     * 注销用户号码映射（置位注销时间戳与删除标识）
     */
    void deletionUser(UserPhoneDO userPhoneDO);
}
