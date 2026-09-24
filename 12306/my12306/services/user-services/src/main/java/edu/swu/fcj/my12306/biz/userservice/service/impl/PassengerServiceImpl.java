package edu.swu.fcj.my12306.biz.userservice.service.impl;

import cn.hutool.core.util.DesensitizedUtil;
import cn.hutool.core.util.IdcardUtil;
import cn.hutool.core.util.PhoneUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.swu.fcj.my12306.biz.userservice.common.ServiceException;
import edu.swu.fcj.my12306.biz.userservice.common.UserContext;
import edu.swu.fcj.my12306.biz.userservice.common.constant.RedisKeyConstant;
import edu.swu.fcj.my12306.biz.userservice.dao.entity.PassengerDO;
import edu.swu.fcj.my12306.biz.userservice.dao.mapper.PassengerMapper;
import edu.swu.fcj.my12306.biz.userservice.dto.req.PassengerRemoveReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.req.PassengerReqDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.PassengerActualRespDTO;
import edu.swu.fcj.my12306.biz.userservice.dto.resp.PassengerRespDTO;
import edu.swu.fcj.my12306.biz.userservice.service.PassengerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 乘车人服务实现（贴近原项目思路）
 * <p>
 * - 列表查询：Cache Aside，user-passenger-list:{username} 缓存 1 天，写操作后删除缓存；
 * - 写操作：新增/修改/移除按 username 加分布式锁防重复提交；
 * - 新增前查重：同用户名下同证件号（解密后比对）只允许一条；
 * - 敏感字段：id_card/phone 由 ShardingSphere AES 加密落库，对外列表在转换时用 hutool 脱敏。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PassengerServiceImpl implements PassengerService {

    /** 列表缓存过期时间：1 天 */
    private static final long PASSENGER_LIST_EXPIRE_DAYS = 1L;

    /** 新增即视为已审核（对齐原项目 VerifyStatusEnum.REVIEWED） */
    private static final int VERIFY_STATUS_REVIEWED = 1;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PassengerMapper passengerMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    @Override
    public List<PassengerRespDTO> listPassengerQueryByUsername(String username) {
        List<PassengerDO> passengerDOList = getActualUserPassengerList(username);
        if (passengerDOList == null || passengerDOList.isEmpty()) {
            return new ArrayList<>();
        }
        return passengerDOList.stream()
                .map(this::convertToMaskedRespDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<PassengerActualRespDTO> listPassengerQueryByIds(String username, List<Long> ids) {
        List<PassengerDO> passengerDOList = getActualUserPassengerList(username);
        if (passengerDOList == null || passengerDOList.isEmpty()) {
            return new ArrayList<>();
        }
        // 内存按 id 过滤：天然保证只返回该 username 名下的乘车人
        return passengerDOList.stream()
                .filter(passengerDO -> ids.contains(passengerDO.getId()))
                .map(this::convertToActualRespDTO)
                .collect(Collectors.toList());
    }

    @Override
    public void savePassenger(PassengerReqDTO requestParam) {
        verifyPassenger(requestParam);
        String username = getCurrentUsername();
        // 分布式锁：同一用户写操作串行化，防双击/并发重复提交
        RLock lock = redissonClient.getLock(RedisKeyConstant.LOCK_USER_PASSENGER_ALTER + username);
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            throw new ServiceException("正在新增乘车人，请稍后再试...");
        }
        try {
            // 查重：按 username 查有效乘车人，解密后比对证件号（不依赖密文等值查询）
            List<PassengerDO> exists = passengerMapper.selectList(Wrappers.lambdaQuery(PassengerDO.class)
                    .eq(PassengerDO::getUsername, username));
            boolean duplicated = exists.stream()
                    .anyMatch(item -> Objects.equals(item.getIdCard(), requestParam.getIdCard()));
            if (duplicated) {
                throw new ServiceException("乘车人已存在");
            }
            PassengerDO passengerDO = PassengerDO.builder()
                    .username(username)
                    .realName(requestParam.getRealName())
                    .idType(requestParam.getIdType())
                    .idCard(requestParam.getIdCard())
                    .discountType(requestParam.getDiscountType())
                    .phone(requestParam.getPhone())
                    .createDate(new Date())
                    .verifyStatus(VERIFY_STATUS_REVIEWED)
                    .build();
            passengerMapper.insert(passengerDO);
        } finally {
            lock.unlock();
        }
        delUserPassengerCache(username);
    }

    @Override
    public void updatePassenger(PassengerReqDTO requestParam) {
        verifyPassenger(requestParam);
        if (!StringUtils.hasText(requestParam.getId())) {
            throw new ServiceException("乘车人ID不能为空");
        }
        String username = getCurrentUsername();
        RLock lock = redissonClient.getLock(RedisKeyConstant.LOCK_USER_PASSENGER_ALTER + username);
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            throw new ServiceException("正在修改乘车人，请稍后再试...");
        }
        try {
            Long passengerId = parsePassengerId(requestParam.getId());
            // username + id 双条件：只允许修改本人名下的乘车人
            PassengerDO updateDO = PassengerDO.builder()
                    .realName(requestParam.getRealName())
                    .idType(requestParam.getIdType())
                    .idCard(requestParam.getIdCard())
                    .discountType(requestParam.getDiscountType())
                    .phone(requestParam.getPhone())
                    .build();
            int updated = passengerMapper.update(updateDO, Wrappers.lambdaUpdate(PassengerDO.class)
                    .eq(PassengerDO::getUsername, username)
                    .eq(PassengerDO::getId, passengerId));
            if (updated == 0) {
                throw new ServiceException("乘车人数据不存在");
            }
        } finally {
            lock.unlock();
        }
        delUserPassengerCache(username);
    }

    @Override
    public void removePassenger(PassengerRemoveReqDTO requestParam) {
        String username = getCurrentUsername();
        RLock lock = redissonClient.getLock(RedisKeyConstant.LOCK_USER_PASSENGER_ALTER + username);
        boolean tryLock = lock.tryLock();
        if (!tryLock) {
            throw new ServiceException("正在移除乘车人，请稍后再试...");
        }
        try {
            Long passengerId = parsePassengerId(requestParam.getId());
            // 先确认记录存在且属于本人，再逻辑删除（MP 会把 delete 转成 del_flag=1 的 update）
            int deleted = passengerMapper.delete(Wrappers.lambdaQuery(PassengerDO.class)
                    .eq(PassengerDO::getUsername, username)
                    .eq(PassengerDO::getId, passengerId));
            if (deleted == 0) {
                throw new ServiceException("乘车人数据不存在");
            }
        } finally {
            lock.unlock();
        }
        delUserPassengerCache(username);
    }

    /**
     * 取当前登录用户；取不到说明未登录
     */
    private String getCurrentUsername() {
        String username = UserContext.getUsername();
        if (!StringUtils.hasText(username)) {
            throw new ServiceException("未登录或登录已过期");
        }
        return username;
    }

    /**
     * 乘车人列表数据源：缓存优先（1 天），未命中回源 DB（按 username 单分片路由）
     * <p>缓存里存的是 ShardingSphere 解密后的 DO JSON；空列表不写缓存（v2 再处理空值穿透）。
     */
    private List<PassengerDO> getActualUserPassengerList(String username) {
        String cacheKey = RedisKeyConstant.USER_PASSENGER_LIST + username;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            try {
                return OBJECT_MAPPER.readValue(cached, new TypeReference<List<PassengerDO>>() {
                });
            } catch (Exception e) {
                log.error("乘车人列表缓存解析失败，key={}", cacheKey, e);
            }
        }
        List<PassengerDO> passengerDOList = passengerMapper.selectList(Wrappers.lambdaQuery(PassengerDO.class)
                .eq(PassengerDO::getUsername, username));
        if (!passengerDOList.isEmpty()) {
            try {
                stringRedisTemplate.opsForValue().set(cacheKey, OBJECT_MAPPER.writeValueAsString(passengerDOList),
                        PASSENGER_LIST_EXPIRE_DAYS, TimeUnit.DAYS);
            } catch (Exception e) {
                log.error("乘车人列表缓存写入失败，key={}", cacheKey, e);
            }
        }
        return passengerDOList;
    }

    /**
     * 写操作成功后删除列表缓存（Cache Aside）
     */
    private void delUserPassengerCache(String username) {
        stringRedisTemplate.delete(RedisKeyConstant.USER_PASSENGER_LIST + username);
    }

    /**
     * DO → 对外脱敏 DTO
     */
    private PassengerRespDTO convertToMaskedRespDTO(PassengerDO passengerDO) {
        PassengerRespDTO respDTO = new PassengerRespDTO();
        respDTO.setId(String.valueOf(passengerDO.getId()));
        respDTO.setUsername(passengerDO.getUsername());
        respDTO.setRealName(passengerDO.getRealName());
        respDTO.setIdType(passengerDO.getIdType());
        // 脱敏：证件号保留前 4 后 4，手机号保留前 3 后 4（与原项目 hutool 规则一致）
        respDTO.setIdCard(DesensitizedUtil.idCardNum(passengerDO.getIdCard(), 4, 4));
        respDTO.setDiscountType(passengerDO.getDiscountType());
        respDTO.setPhone(DesensitizedUtil.mobilePhone(passengerDO.getPhone()));
        respDTO.setCreateDate(passengerDO.getCreateDate());
        respDTO.setVerifyStatus(passengerDO.getVerifyStatus());
        return respDTO;
    }

    /**
     * DO → 内部明文 DTO
     */
    private PassengerActualRespDTO convertToActualRespDTO(PassengerDO passengerDO) {
        PassengerActualRespDTO respDTO = new PassengerActualRespDTO();
        respDTO.setId(String.valueOf(passengerDO.getId()));
        respDTO.setUsername(passengerDO.getUsername());
        respDTO.setRealName(passengerDO.getRealName());
        respDTO.setIdType(passengerDO.getIdType());
        respDTO.setIdCard(passengerDO.getIdCard());
        respDTO.setDiscountType(passengerDO.getDiscountType());
        respDTO.setPhone(passengerDO.getPhone());
        respDTO.setCreateDate(passengerDO.getCreateDate());
        respDTO.setVerifyStatus(passengerDO.getVerifyStatus());
        return respDTO;
    }

    /**
     * id 字符串转 Long（雪花 ID 超 JS 安全整数，前端用字符串传）
     */
    private Long parsePassengerId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ServiceException("乘车人数据不存在");
        }
    }

    /**
     * 乘车人参数合法性校验（对齐原项目 hutool 规则与文案）
     */
    private void verifyPassenger(PassengerReqDTO requestParam) {
        if (StrUtil.hasBlank(requestParam.getRealName(), requestParam.getIdCard(), requestParam.getPhone())) {
            throw new ServiceException("乘车人名称、证件号、手机号不能为空");
        }
        int length = requestParam.getRealName().length();
        if (length < 2 || length > 16) {
            throw new ServiceException("乘车人名称请设置2-16位的长度");
        }
        if (!IdcardUtil.isValidCard(requestParam.getIdCard())) {
            throw new ServiceException("乘车人证件号错误");
        }
        if (!PhoneUtil.isMobile(requestParam.getPhone())) {
            throw new ServiceException("乘车人手机号错误");
        }
    }
}
