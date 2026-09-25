package com.apesource.accountservice.service;

import com.apesource.accountservice.dto.LoginRequest;
import com.apesource.accountservice.dto.LoginResponse;
import com.apesource.accountservice.dto.RegisterRequest;
import com.apesource.accountservice.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 用户业务接口。
 * <p>
 * 继承 MP 的 {@link IService} 直接获得 save/updateById/getById 等通用方法，
 * 自定义方法只声明注册登录这类真正有业务含义的操作。
 */
public interface UserService extends IService<User> {

    /**
     * 注册新用户。
     *
     * @param request 注册参数
     * @return 新用户主键
     */
    Long register(RegisterRequest request);

    /**
     * 登录并签发 token。
     *
     * @param request 登录参数
     * @return token 与用户基础信息
     */
    LoginResponse login(LoginRequest request);

    /**
     * 按用户名查询用户，主要给内部调用。
     *
     * @param username 用户名
     * @return 用户实体，不存在返回 null
     */
    User getByUsername(String username);
}
