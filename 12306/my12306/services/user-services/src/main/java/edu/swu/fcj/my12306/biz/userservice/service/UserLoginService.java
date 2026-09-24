package edu.swu.fcj.my12306.biz.userservice.service;

import com.baomidou.mybatisplus.extension.service.IService;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserDO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserDeletionReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserLoginReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserRegisterReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.UserLoginRespDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.UserRegisterRespDTO;

public interface UserLoginService extends IService<UserDO> {

    /**
     * 判断用户名是否可用
     * @param username
     * @return
     */
    Boolean hasUsername(String username);

    /**
     * 注册接口
     * @param requestParam
     * @return
     */
    UserRegisterRespDTO register(UserRegisterReqDTO requestParam);

    /**
     * 登录：支持用户名/手机号/邮箱三种身份
     * @param requestParam 账号 + 密码
     * @return 登录信息 + accessToken
     */
    UserLoginRespDTO login(UserLoginReqDTO requestParam);

    /**
     * 登录态检查：查 Redis 中的 accessToken
     */
    UserLoginRespDTO checkLogin(String accessToken);

    /**
     * 登出：删除 Redis 中的 accessToken
     */
    void logout(String accessToken);

    /**
     * 注销账号：校验登录态与本人 → 登记 + 三表置位 + 删 token + 写复用记录
     *
     * @param requestParam 注销请求（username）
     */
    void deletion(UserDeletionReqDTO requestParam);
}
