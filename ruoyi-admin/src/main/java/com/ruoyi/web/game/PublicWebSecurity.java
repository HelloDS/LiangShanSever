package com.ruoyi.web.game;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class PublicWebSecurity {
    @Bean @Order(100)
    public SecurityFilterChain publicGameChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(r->r.requestMatchers("/api/auth/**","/cgc_server/**","/admin/**","/error").permitAll().anyRequest().denyAll()).build();
    }
}
