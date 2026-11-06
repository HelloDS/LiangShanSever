package com.ruoyi;

import com.ruoyi.web.game.AdminBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminBootstrapTest {
    @Test void passwordOutsideLoginLimitsCannotInitializeAnUnusableAdministrator(){
        for(String password:new String[]{"a".repeat(11),"a".repeat(21),"a".repeat(43)}){
            var jdbc=mock(JdbcTemplate.class);var encoder=mock(PasswordEncoder.class);
            assertThrows(IllegalStateException.class,()->new AdminBootstrap(jdbc,encoder,password).run(new DefaultApplicationArguments()));
            verifyNoInteractions(jdbc,encoder);
        }
    }
    @Test void acceptedBoundariesOnlyInitializeTheUntouchedAdministrator(){
        for(int length:new int[]{12,20}){
            var jdbc=mock(JdbcTemplate.class);var encoder=mock(PasswordEncoder.class);String password="a".repeat(length);
            when(encoder.encode(password)).thenReturn("hashed-password");
            new AdminBootstrap(jdbc,encoder,password).run(new DefaultApplicationArguments());
            verify(jdbc).update("UPDATE sys_user SET password=?,status='0',nick_name='平台管理员' WHERE user_id=1 AND password='!UNINITIALIZED'","hashed-password");
            verifyNoMoreInteractions(jdbc);
        }
    }
    @Test void omittedPasswordLeavesTheAdministratorDisabled(){
        var jdbc=mock(JdbcTemplate.class);var encoder=mock(PasswordEncoder.class);
        new AdminBootstrap(jdbc,encoder,"").run(new DefaultApplicationArguments());
        verifyNoInteractions(jdbc,encoder);
    }
}
