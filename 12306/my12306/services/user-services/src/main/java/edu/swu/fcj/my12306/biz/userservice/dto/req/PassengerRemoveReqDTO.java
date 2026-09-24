package edu.swu.fcj.my12306.biz.userservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 乘车人移除请求
 */
@Data
public class PassengerRemoveReqDTO {

    @NotBlank(message = "乘车人ID不能为空")
    private String id;
}
