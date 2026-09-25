package com.apesource.gateway;

import com.apesource.gateway.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动自检：在网关启动完成后打印关键配置，并校验配置自洽性。
 * <p>
 * 为什么需要它：网关的认证出问题时，现象往往是「所有接口都 401」，
 * 这时候第一反应是去看 token，但真正的原因可能是白名单写错、
 * 或者密钥和 account-service 不一致。启动时把这两件事打到日志里，
 * 排障成本能从「半小时」降到「一眼」。
 */
@Component
public class GatewayStartupChecker implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GatewayStartupChecker.class);

    private final AuthProperties authProperties;

    public GatewayStartupChecker(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("================ 网关认证配置自检 ================");
        authProperties.validate();
        log.info("提示：若下游服务收到 401，请先确认 gateway 与 account-service 的 jwt.secret 是否完全一致");
        log.info("================================================");
    }
}
