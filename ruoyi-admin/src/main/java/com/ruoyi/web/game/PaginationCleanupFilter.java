package com.ruoyi.web.game;

import java.io.IOException;
import com.github.pagehelper.PageHelper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 后台查询异常也不能把 PageHelper 线程状态带入复用 Tomcat 线程的游戏请求。 */
@Component
@Order(-90)
public class PaginationCleanupFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        PageHelper.clearPage();
        try{chain.doFilter(request,response);}finally{PageHelper.clearPage();}
    }
}
