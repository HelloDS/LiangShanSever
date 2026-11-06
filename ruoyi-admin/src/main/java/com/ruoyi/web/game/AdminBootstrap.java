package com.ruoyi.web.game;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.UserConstants;

/** 只初始化尚未启用的管理员；重启不覆盖已设置的密码。 */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String initialPassword;
    public AdminBootstrap(JdbcTemplate jdbc,PasswordEncoder encoder,@Value("${ADMIN_INITIAL_PASSWORD:}") String password) {
        this.jdbc=jdbc;this.encoder=encoder;this.initialPassword=password;
    }
    @Override public void run(ApplicationArguments args) {
        if(initialPassword.isBlank())return;
        if(initialPassword.length()<12||initialPassword.length()>UserConstants.PASSWORD_MAX_LENGTH)
            throw new IllegalStateException("管理员初始密码须为 12 至 "+UserConstants.PASSWORD_MAX_LENGTH+" 位");
        jdbc.update("UPDATE sys_user SET password=?,status='0',nick_name='平台管理员' WHERE user_id=1 AND password='!UNINITIALIZED'",encoder.encode(initialPassword));
    }
}
