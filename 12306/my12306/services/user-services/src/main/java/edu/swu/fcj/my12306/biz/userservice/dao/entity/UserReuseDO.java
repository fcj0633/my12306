package edu.swu.fcj.my12306.biz.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户名复用实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user_reuse")
public final class UserReuseDO extends BaseDO {

    private String username;
}
