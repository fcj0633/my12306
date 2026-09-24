package edu.swu.fcj.my12306.biz.userservice.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 乘车人真实信息返回（内部接口专用）：证件号/手机号为明文
 */
@Data
public class PassengerActualRespDTO {

    private String id;

    private String username;

    private String realName;

    private Integer idType;

    private String idCard;

    private Integer discountType;

    private String phone;

    @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
    private Date createDate;

    private Integer verifyStatus;
}
