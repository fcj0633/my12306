package edu.swu.fcj.my12306.biz.userservice.dto.resp;

import lombok.Data;

/**
 * 用户信息查询响应
 */
@Data
public class UserQueryRespDTO {

    private String username;

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
}
