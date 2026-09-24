package edu.swu.fcj.my12306.biz.userservice.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 乘车人返回（对外）：证件号/手机号已脱敏
 */
@Data
public class PassengerRespDTO {

    private String id;

    private String username;

    private String realName;

    private Integer idType;

    /** 脱敏后的证件号（前 4 后 4） */
    private String idCard;

    private Integer discountType;

    /** 脱敏后的手机号（如 139****9911） */
    private String phone;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private Date createDate;

    private Integer verifyStatus;
}
