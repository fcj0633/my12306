package edu.swu.fcj.my12306.biz.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户实体
 */
@Data
@TableName("t_user")
public class UserDO extends BaseDO {

    private Long id;

    private String username;

    private String password;

    private String realName;

    private String region;

    private Integer idType;

    private String idCard;

    private String phone;

    private String telephone;

    private String mail;

    private Integer userType;

    private Integer verifyStatus;

    private String postCode;

    private String address;

    private Long deletionTime;
}
