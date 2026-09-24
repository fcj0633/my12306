package edu.swu.fcj.my12306.biz.userservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 乘车人新增/修改请求（修改时 id 必填；格式合法性在 Service 层用 hutool 校验）
 */
@Data
public class PassengerReqDTO {

    /** 乘车人 ID（修改时必填，新增不传） */
    private String id;

    @NotBlank(message = "乘车人名称不能为空")
    private String realName;

    @NotNull(message = "证件类型不能为空")
    private Integer idType;

    @NotBlank(message = "证件号码不能为空")
    private String idCard;

    /** 优惠类型（选填） */
    private Integer discountType;

    @NotBlank(message = "手机号不能为空")
    private String phone;
}
