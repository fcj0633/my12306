package edu.swu.fcj.my12306.biz.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户注销登记实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_user_deletion")
public class UserDeletionDO extends BaseDO {

    private Long id;

    private Integer idType;

    private String idCard;
}
