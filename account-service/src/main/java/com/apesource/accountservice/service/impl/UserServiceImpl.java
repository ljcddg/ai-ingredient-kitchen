package com.apesource.accountservice.service.impl;

import com.apesource.accountservice.bloom.UserBloomFilter;
import com.apesource.accountservice.dto.LoginRequest;
import com.apesource.accountservice.dto.LoginResponse;
import com.apesource.accountservice.dto.RegisterRequest;
import com.apesource.accountservice.entity.User;
import com.apesource.accountservice.mapper.UserMapper;
import com.apesource.accountservice.service.UserService;
import com.apesource.commonservice.exception.BusinessException;
import com.apesource.commonservice.util.JwtUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户业务实现。
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    /** 账号状态：正常 */
    private static final int STATUS_ENABLED = 1;

    /**
     * BCrypt 编码器。
     * <p>
     * BCrypt 自带随机盐，同一个密码每次 encode 结果都不同，
     * 所以校验必须用 {@code matches(明文, 密文)} 而不是比较字符串。
     */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final JwtUtil jwtUtil;

    private final UserBloomFilter userBloomFilter;

    public UserServiceImpl(JwtUtil jwtUtil, UserBloomFilter userBloomFilter) {
        this.jwtUtil = jwtUtil;
        this.userBloomFilter = userBloomFilter;
    }

    @Override
    public User getByUsername(String username) {
        return getOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterRequest request) {
        String username = request.getUsername();

        // 1. 布隆过滤器快速筛：说「不存在」就一定不存在，可以直接进注册流程，省一次查库。
        //    说「可能存在」有误判率，必须再查库确认 —— 所以这里不能直接拒绝。
        if (userBloomFilter.mightContain(username)) {
            // 2. 布隆说可能存在，查库拿到确切结论
            if (getByUsername(username) != null) {
                throw new BusinessException("用户名已存在");
            }
            // 走到这里说明是布隆误判，用户其实不存在，正常放行
            log.debug("布隆过滤器误判，用户名[{}]实际不存在，正常放行", username);
        }

        User user = new User();
        user.setUsername(username);
        // 3. 密码加密后再入库，明文永远不落库
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        // 4. 昵称缺省时用用户名兜底
        user.setNickname(request.getNickname() == null || request.getNickname().isBlank()
                ? username
                : request.getNickname());
        user.setStatus(STATUS_ENABLED);

        save(user);

        // 5. 注册成功后必须写进布隆过滤器，否则该用户后续会被误判为「不存在」
        userBloomFilter.add(username);

        return user.getId();
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        String username = request.getUsername();

        // 布隆过滤器前置拦截：说「不存在」就直接按「用户名或密码错误」返回，不查库。
        // 这是防缓存穿透的关键 —— 攻击者用大量随机用户名刷登录时，全被挡在数据库之外。
        if (!userBloomFilter.mightContain(username)) {
            throw new BusinessException("用户名或密码错误");
        }

        User user = getByUsername(username);
        // 统一提示「用户名或密码错误」，不区分是账号不存在还是密码错，
        // 避免攻击者借此枚举出系统里有哪些用户名
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() != STATUS_ENABLED) {
            throw new BusinessException("账号已被禁用，请联系管理员");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return new LoginResponse(token, user.getId(), user.getUsername(), user.getNickname());
    }
}
