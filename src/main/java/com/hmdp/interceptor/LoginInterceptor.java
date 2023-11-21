package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @Author 帅博文
 * @Date 2023/11/16 9:22
 * @Version 1.0
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 通过threadLocal获取登录信息，校验用户信息是否存在
        if (UserHolder.getUser() == null) {
            // 不存在，拦截请求，返回401状态码
            response.setStatus(401);
            return false;
        }
        // 放行
        return true;
    }
}
