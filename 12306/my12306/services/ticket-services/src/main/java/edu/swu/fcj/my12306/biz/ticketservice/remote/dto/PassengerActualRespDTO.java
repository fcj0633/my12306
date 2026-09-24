package edu.swu.fcj.my12306.biz.ticketservice.remote.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 乘车人实名明文信息（用户服务内部接口返回）
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
