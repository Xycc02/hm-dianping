package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static net.sf.jsqlparser.util.validation.metadata.NamedObject.user;

/**
 * @Author: xuyuchao
 * @Date: 2022-08-29-12:55
 * @Description: 请求拦截器
 */
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {

    /**
     * 前置拦截
     * @param request
     * @param response
     * @param handler
     * @return
     * @throws Exception
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // //1.获取session
        // // HttpSession session = request.getSession();
        // //1.获取请求头中的token
        // String token = request.getHeader("authorization");
        // log.debug("token:{}",token);
        // //3.判断token是否为空
        // if(StrUtil.isBlank(token)) {
        //     //设置状态码
        //     response.setStatus(401);
        //     //拦截
        //     return false;
        // }
        // //2.获取session中的用户
        // // Object user = session.getAttribute("user");
        // //2.根据token从Redis中获取用户信息
        // Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        // //3.判断用户是否存在
        // if(userMap.isEmpty()) {
        //     //设置状态码
        //     response.setStatus(401);
        //     //拦截
        //     return false;
        // }
        // //4.刷新token有效期
        // stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.SECONDS);
        // //5.用户存在,将用户保存到threadLocal,放行
        // //5.1将用户信息由hashmap转为userDto对象
        // UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // UserHolder.saveUser(userDTO);
        // return true;


        //刷新token由上一层拦截器完成,这层拦截器只做登录拦截(判断ThreadLocal中有没有存在用户信息)
        if(UserHolder.getUser() == null) {
            //设置状态码
            response.setStatus(401);
            //拦截
            return false;
        }
        return true;
    }

}
