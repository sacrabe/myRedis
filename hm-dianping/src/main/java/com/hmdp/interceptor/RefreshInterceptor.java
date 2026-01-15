package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;
@Component
public class RefreshInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中token
        // HttpSession session = request.getSession();
        String token = request.getHeader("authorization");

        if(StrUtil.isBlank(token)){

            return true;
        }
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        // 2.基于token获取用户
        Map<Object, Object> userEntries = stringRedisTemplate.opsForHash().
                entries(tokenKey);
        // 未登录，返回登录页面
        if(userEntries.isEmpty()){

            return true;
        }

        // 获取 用户
        UserDTO userDTO  = BeanUtil.fillBeanWithMap(userEntries, new UserDTO(), false);
        //UserDTO userDTO = (UserDTO) session.getAttribute("user");

        // 已登录，存入threadlocal

        UserHolder.saveUser(userDTO);
        //stringRedisTemplate.expire(tokenKey, RedisConstants.LOGIN_USER_TTL, TimeUnit.SECONDS);

        return true;
    }


}
