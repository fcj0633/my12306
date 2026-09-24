package edu.swu.fcj.my12306.biz.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 乘车人实体（逻辑表 t_passenger，按 username 分片，id_card/phone 加密落库）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_passenger")
public class PassengerDO extends BaseDO {

    /** 乘车人 ID（应用侧雪花主键） */
    private Long id;

    /** 归属用户名（分片键） */
    private String username;

    /** 真实姓名 */
    private String realName;

    /** 证件类型 */
    private Integer idType;

    /** 证件号码（AES 密文落库） */
    private String idCard;

    /** 优惠类型 */
    private Integer discountType;

    /** 手机号（AES 密文落库） */
    private String phone;

    /** 添加日期（业务日期，区别于公共字段 createTime） */
    private Date createDate;

    /** 审核状态：0=未审核 1=已审核 */
    private Integer verifyStatus;
}
