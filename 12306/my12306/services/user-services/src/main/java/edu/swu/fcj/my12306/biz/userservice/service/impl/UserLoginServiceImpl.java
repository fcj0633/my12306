package edu.swu.fcj.my12306.biz.userservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.swu.fcj.my12306.biz.userservice.common.JwtUtil;
import edu.swu.fcj.my12306.biz.userservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.userservice.common.UserContext;
import edu.swu.fcj.my12306.biz.userservice.common.UserInfoDTO;
import edu.swu.fcj.my12306.biz.userservice.common.chain.AbstractChainContext;
import edu.swu.fcj.my12306.biz.userservice.common.constant.RedisKeyConstant;
import edu.swu.fcj.my12306.biz.userservice.common.constant.UserChainMarkEnum;
import edu.swu.fcj.my12306.biz.userservice.common.toolkit.UserReuseUtil;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserDO;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserDeletionDO;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserMailDO;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserPhoneDO;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.UserReuseDO;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserDeletionMapper;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserMailMapper;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserMapper;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserPhoneMapper;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.UserReuseMapper;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserDeletionReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserLoginReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.UserRegisterReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.UserLoginRespDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.UserRegisterRespDTO;
import edu.swu.fcj.my12306.biz.userservice.service.UserLoginService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 用户登录服务实现（v2）：注册 / 登录 / 注销
 * <p>
 * v2 要点：
 * - 注册校验走责任链（参数 → 用户名可用性 → 证件黑名单）；
 * - 用户名可用性 = 布隆过滤器（防穿透）+ Redis 复用集合（已注销可复用）；
 * - 注册/注销加分布式锁串行化并发；
 * - 注销从 UserContext 取当前登录用户（不再接口传 token）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserLoginServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserLoginService {

    /** 登录态缓存过期时间（分钟） */
    private static final long LOGIN_TOKEN_EXPIRE_MINUTES = 30L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserPhoneMapper userPhoneMapper;
    private final UserMailMapper userMailMapper;
    private final UserDeletionMapper userDeletionMapper;
    private final UserReuseMapper userReuseMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final RBloomFilter<String> userRegisterCachePenetrationBloomFilter;
    private final AbstractChainContext abstractChainContext;

    /**
     * 判断用户名是否可用（v2：布隆过滤器 + 复用集合，不打数据库）
     * <p>
     * 判定表：
     * 布隆不含 → 一定没注册过 → 可用；
     * 布隆含 → 查 user-reuse:{分片}：在 = 已注销可复用 → 可用；不在 = 在用 → 不可用。
     */
    @Override
    public Boolean hasUsername(String username) {
        boolean inBloom = userRegisterCachePenetrationBloomFilter.contains(username);
        if (inBloom) {
            return stringRedisTemplate.opsForSet().isMember(
                    RedisKeyConstant.USER_REGISTER_REUSE_SHARDING + UserReuseUtil.hashShardingIdx(username), username);
        }
        return true;
    }

    /**
     * 注册（v2）：
     * 责任链校验（参数非空 → 用户名可用性 → 证件黑名单）→ 手机号/邮箱预检 →
     * 分布式锁 tryLock → 事务内三表插入（唯一索引兜底）→ 删复用记录 → 集合 remove → 布隆 add
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserRegisterRespDTO register(UserRegisterReqDTO requestParam) {
        // 1. 责任链：参数非空 → hasUsername（布隆+集合）→ 证件黑名单 >= 5
        abstractChainContext.handler(UserChainMarkEnum.USER_REGISTER_FILTER.name(), requestParam);
        // 2. 手机号 / 邮箱唯一性预检：提前给出友好提示（并发瞬间仍由唯一索引兜底）
        if (userPhoneMapper.selectCount(Wrappers.lambdaQuery(UserPhoneDO.class)
                .eq(UserPhoneDO::getPhone, requestParam.getPhone())
                .eq(UserPhoneDO::getDelFlag, 0)) > 0) {
            throw new ServiceException("手机号已注册");
        }
        if (userMailMapper.selectCount(Wrappers.lambdaQuery(UserMailDO.class)
                .eq(UserMailDO::getMail, requestParam.getMail())
                .eq(UserMailDO::getDelFlag, 0)) > 0) {
            throw new ServiceException("邮箱已注册");
        }
        // 3. 分布式锁：同一用户名注册串行化；tryLock 失败说明正在被注册
        RLock lock = redissonClient.getLock(RedisKeyConstant.LOCK_USER_REGISTER + requestParam.getUsername());
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            throw new ServiceException("用户名已存在");
        }
        try {
            // 4. BCrypt 加密后落库（密文 60 位，与 varchar(60) 匹配）
            String encodedPassword = passwordEncoder.encode(requestParam.getPassword());
            // 5. 主表：账号 + 实名信息（verifyState → verifyStatus 字段名不同）
            UserDO userDO = new UserDO();
            userDO.setUsername(requestParam.getUsername());
            userDO.setPassword(encodedPassword);
            userDO.setRealName(requestParam.getRealName());
            userDO.setRegion(requestParam.getRegion());
            userDO.setIdType(requestParam.getIdType());
            userDO.setIdCard(requestParam.getIdCard());
            userDO.setPhone(requestParam.getPhone());
            userDO.setTelephone(requestParam.getTelephone());
            userDO.setMail(requestParam.getMail());
            userDO.setUserType(requestParam.getUserType());
            userDO.setVerifyStatus(requestParam.getVerifyState());
            userDO.setPostCode(requestParam.getPostCode());
            userDO.setAddress(requestParam.getAddress());
            try {
                baseMapper.insert(userDO);
            } catch (DuplicateKeyException e) {
                throw new ServiceException("用户名已存在");
            }
            // 6. 手机号映射表
            try {
                userPhoneMapper.insert(UserPhoneDO.builder()
                        .username(requestParam.getUsername())
                        .phone(requestParam.getPhone())
                        .build());
            } catch (DuplicateKeyException e) {
                throw new ServiceException("手机号已注册");
            }
            // 7. 邮箱映射表（防御性判断，DTO 已强制必填）
            if (requestParam.getMail() != null && !requestParam.getMail().isBlank()) {
                try {
                    userMailMapper.insert(UserMailDO.builder()
                            .username(requestParam.getUsername())
                            .mail(requestParam.getMail())
                            .build());
                } catch (DuplicateKeyException e) {
                    throw new ServiceException("邮箱已注册");
                }
            }
            // 8. 注册成功即占用：删复用记录（DB + Redis 集合）
            userReuseMapper.delete(Wrappers.lambdaQuery(UserReuseDO.class)
                    .eq(UserReuseDO::getUsername, requestParam.getUsername()));
            stringRedisTemplate.opsForSet().remove(
                    RedisKeyConstant.USER_REGISTER_REUSE_SHARDING + UserReuseUtil.hashShardingIdx(requestParam.getUsername()),
                    requestParam.getUsername());
            // 9. 布隆过滤器标记"注册过"（防穿透的"一定不存在"判断前提）
            userRegisterCachePenetrationBloomFilter.add(requestParam.getUsername());
        } finally {
            lock.unlock();
        }
        // 10. 组装响应（不回传密码等敏感信息）
        UserRegisterRespDTO respDTO = new UserRegisterRespDTO();
        respDTO.setUsername(requestParam.getUsername());
        respDTO.setRealName(requestParam.getRealName());
        respDTO.setPhone(requestParam.getPhone());
        return respDTO;
    }

    /**
     * 登录：识别身份（@ 即邮箱，否则先按手机号查、查不到当用户名）→ 主表校验密码 → 签发 JWT → 缓存登录态
     */
    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam) {
        String usernameOrMailOrPhone = requestParam.getUsernameOrMailOrPhone();
        // 1. 识别类型：约定含 @ 视为邮箱（注册侧已禁止用户名含 @）
        boolean mailFlag = false;
        for (char c : usernameOrMailOrPhone.toCharArray()) {
            if (c == '@') {
                mailFlag = true;
                break;
            }
        }
        String username;
        if (mailFlag) {
            // 2.1 邮箱：查映射表拿 username（del_flag=0，注销用户的邮箱不可用）
            UserMailDO userMailDO = userMailMapper.selectOne(Wrappers.lambdaQuery(UserMailDO.class)
                    .eq(UserMailDO::getMail, usernameOrMailOrPhone)
                    .eq(UserMailDO::getDelFlag, 0));
            if (userMailDO == null) {
                throw new ServiceException("用户名/手机号/邮箱不存在");
            }
            username = userMailDO.getUsername();
        } else {
            // 2.2 非邮箱：先按手机号查，查不到则把输入原值当 username
            UserPhoneDO userPhoneDO = userPhoneMapper.selectOne(Wrappers.lambdaQuery(UserPhoneDO.class)
                    .eq(UserPhoneDO::getPhone, usernameOrMailOrPhone)
                    .eq(UserPhoneDO::getDelFlag, 0));
            username = userPhoneDO != null ? userPhoneDO.getUsername() : usernameOrMailOrPhone;
        }
        // 3. 主表校验：账号存在且未注销（del_flag=0），BCrypt matches 校验密文
        UserDO userDO = baseMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username)
                .eq(UserDO::getDelFlag, 0));
        // 账号不存在与密码错误统一文案，防账号枚举
        if (userDO == null || !passwordEncoder.matches(requestParam.getPassword(), userDO.getPassword())) {
            throw new ServiceException("账号不存在或密码错误");
        }
        // 4. 签发 JWT（载荷只放非敏感信息）
        UserInfoDTO userInfo = UserInfoDTO.builder()
                .userId(String.valueOf(userDO.getId()))
                .username(userDO.getUsername())
                .realName(userDO.getRealName())
                .build();
        String accessToken = JwtUtil.generateAccessToken(userInfo);
        UserLoginRespDTO loginResp = UserLoginRespDTO.builder()
                .userId(userInfo.getUserId())
                .username(userInfo.getUsername())
                .realName(userInfo.getRealName())
                .accessToken(accessToken)
                .build();
        // 5. 登录态缓存：key=accessToken，value=登录信息 JSON，TTL 30 分钟
        try {
            stringRedisTemplate.opsForValue().set(accessToken, OBJECT_MAPPER.writeValueAsString(loginResp),
                    LOGIN_TOKEN_EXPIRE_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("登录状态缓存失败，token={}", accessToken, e);
            throw new ServiceException("登录状态缓存失败");
        }
        return loginResp;
    }

    /**
     * 登录态检查：按 accessToken 查 Redis，查到返回登录信息，查不到返回 null
     */
    @Override
    public UserLoginRespDTO checkLogin(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            return null;
        }
        String value = stringRedisTemplate.opsForValue().get(accessToken);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(value, UserLoginRespDTO.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 登出：删除 Redis 中的 accessToken，登录态立即失效
     */
    @Override
    public void logout(String accessToken) {
        if (StringUtils.hasText(accessToken)) {
            stringRedisTemplate.delete(accessToken);
        }
    }

    /**
     * 注销（v2）：当前登录用户取自 UserContext（由过滤器写入），
     * 分布式锁（lock 放 try 外）→ 事务内登记/三表置位/删 token/写复用记录 → 复用集合 add
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletion(UserDeletionReqDTO requestParam) {
        // 1. 当前登录用户（UserContext）：未登录直接拒绝
        String currentUsername = UserContext.getUsername();
        if (!StringUtils.hasText(currentUsername)) {
            throw new ServiceException("未登录或登录已过期");
        }
        // 2. 只能注销本人账号
        if (!Objects.equals(currentUsername, requestParam.getUsername())) {
            throw new ServiceException("注销账号与登录账号不一致");
        }
        String token = UserContext.getToken();
        // 3. 分布式锁（放在 try 外：确保拿到锁才进入受保护代码，避免对未获得锁 unlock）
        RLock lock = redissonClient.getLock(RedisKeyConstant.USER_DELETION + requestParam.getUsername());
        lock.lock();
        try {
            // 4. 查用户实名信息：登记表需要证件，映射表置位需要 phone/mail
            UserDO userDO = baseMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                    .eq(UserDO::getUsername, requestParam.getUsername())
                    .eq(UserDO::getDelFlag, 0));
            if (userDO == null) {
                throw new ServiceException("账号不存在");
            }
            long deletionTime = System.currentTimeMillis();
            // 5. 登记注销：证件号进 t_user_deletion（黑名单依据）
            userDeletionMapper.insert(UserDeletionDO.builder()
                    .idType(userDO.getIdType())
                    .idCard(userDO.getIdCard())
                    .build());
            // 6. 主表置位：deletion_time 让位唯一索引 + del_flag=1 查询不可见
            UserDO updateUser = new UserDO();
            updateUser.setUsername(userDO.getUsername());
            updateUser.setDeletionTime(deletionTime);
            baseMapper.deletionUser(updateUser);
            // 7. 手机号 / 邮箱映射同步置位
            userPhoneMapper.deletionUser(UserPhoneDO.builder()
                    .phone(userDO.getPhone())
                    .deletionTime(deletionTime)
                    .build());
            if (userDO.getMail() != null && !userDO.getMail().isBlank()) {
                userMailMapper.deletionUser(UserMailDO.builder()
                        .mail(userDO.getMail())
                        .deletionTime(deletionTime)
                        .build());
            }
            // 8. 删除登录态 token
            if (StringUtils.hasText(token)) {
                stringRedisTemplate.delete(token);
            }
            // 9. 写复用记录（DB 落库，防 Redis 丢失）
            userReuseMapper.insert(new UserReuseDO(requestParam.getUsername()));
            // 10. Redis 复用集合 add：hasUsername 的"已注销可复用"判断依据
            stringRedisTemplate.opsForSet().add(
                    RedisKeyConstant.USER_REGISTER_REUSE_SHARDING + UserReuseUtil.hashShardingIdx(requestParam.getUsername()),
                    requestParam.getUsername());
        } finally {
            lock.unlock();
        }
    }
}
