package edu.swu.fcj.my12306.biz.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户号码映射实体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("t_user_phone")
public class UserPhoneDO extends BaseDO {

    private Long id;

    private String username;

    private String phone;

    private Long deletionTime;
}
