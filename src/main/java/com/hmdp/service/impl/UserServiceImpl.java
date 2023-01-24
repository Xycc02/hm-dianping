package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送短信验证码并保存验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
            //2.手机号不符合
            return Result.fail("手机号格式错误!");
        }
        //3.手机号格式正确,生成验证码,hutool工具包
        String code = RandomUtil.randomNumbers(6);
        //4.将验证码存入session
        // session.setAttribute("code",code);
        //4.将验证码存入redis中,key为手机号,value为验证码,并设置过期时间
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("验证码:{}",code);
        return Result.ok();
    }

    /**
     * 实现登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            //2.手机号格式错误
            return Result.fail("手机号格式错误!");
        }
        //3.校验验证码(Session)
        // Object cacheCode = session.getAttribute("code");
        //3.校验验证码(Redis)
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(StringUtils.isEmpty(cacheCode) || !cacheCode.equals(code)) {
            //4.该手机号和验证码不匹配
            return Result.fail("验证码错误!");
        }
        //5.验证码正确,查询数据库是否存在该手机号用户
        User user = this.getOne(new QueryWrapper<User>().eq("phone", phone));
        if(user == null) {
            //6.数据库中不存在该用户,则创建该用户
            user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            this.save(user);
        }
        //7.若数据库中存在该用户则直接登录,并保存用户到session
        // UserDTO userDTO = new UserDTO();
        // BeanUtils.copyProperties(user,userDTO);
        // session.setAttribute("user",userDTO);
        //7.若数据库中存在该用户则直接登录,并保存用户到Redis
        //7.1 随机生成token作为登录令牌作为key
        String token = UUID.randomUUID().toString(true);
        //7.2将user对象转为hash格式存储到Redis中
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.3 设置token的有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL,TimeUnit.SECONDS);
        //8将token返回给客户端
        return Result.ok(token);
    }
}
