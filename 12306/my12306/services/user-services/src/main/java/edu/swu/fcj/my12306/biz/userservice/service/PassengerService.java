package edu.swu.fcj.my12306.biz.userservice.service;

import edu.swu.fcj.my12306.biz.userservice.dto.req.PassengerRemoveReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.PassengerReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.PassengerRespDTO;

import java.util.List;

/**
 * 乘车人业务接口：列表（脱敏）/ 内部按 ID 集合（明文）/ 新增 / 修改 / 移除
 */
public interface PassengerService {

    /**
     * 查询当前用户名下的乘车人列表（对外，证件/手机号脱敏）
     */
    List<PassengerRespDTO> listPassengerQueryByUsername(String username);

    /**
     * 按 ID 集合查询乘车人（内部接口，返回明文，供购票服务核验）
     */
    List<PassengerActualRespDTO> listPassengerQueryByIds(String username, List<Long> ids);

    /**
     * 新增乘车人
     */
    void savePassenger(PassengerReqDTO requestParam);

    /**
     * 修改乘车人
     */
    void updatePassenger(PassengerReqDTO requestParam);

    /**
     * 移除乘车人（逻辑删除）
     */
    void removePassenger(PassengerRemoveReqDTO requestParam);
}
