package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
    @Override
    public Result sendCode(String phone, HttpSession session) {

        //1. 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2. 如果不符合，返回错误
            return Result.fail("手机号格式错误");
        }

        //3. 符合，生成验证码
        String code= RandomUtil.randomNumbers(6);

        //4. save the code to redis
        //set key value ex 120
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone, code,  RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5. 发送验证码
        log.debug("发送短信验证码成功, code:{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginFormDTO, HttpSession session) {
        //1. 校验手机号
        if(RegexUtils.isPhoneInvalid(loginFormDTO.getPhone())){
            //2. 如果不符合，返回错误
            return Result.fail("手机号格式错误");
        }
        //3. 校验验证码
        Object cachedCode=stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY+loginFormDTO.getPhone());
        String code=loginFormDTO.getCode();
        if(cachedCode==null||!cachedCode.equals(code)){
            //4. 验证码不一致报错
            return Result.fail("验证码错误");
        }

        //5. 一致，根据手机号查询用户
        User user=query().eq("phone",loginFormDTO.getPhone()).one();
        //6.判断用户是否存在
        if(user==null){
            //7.不存在，创建新用户并且保存
            user=createUserWithPhone(loginFormDTO.getPhone());
        }
        //8. 保存用户信息到redis
        //8.1 随机生成token，作为登录令牌
        String token=UUID.randomUUID().toString().replace("-","");
        //8.2 将User对象转为Hash存储
        UserDTO userDTO= BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName, fieldValue)->fieldValue.toString()));
        //8.3 存储
        String tokenKey=RedisConstants.LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,stringObjectMap);
        //8.4 设置token有效期
        stringRedisTemplate.expire(tokenKey,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }
    private User createUserWithPhone(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setPassword(RandomUtil.randomNumbers(6));
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(6));
        //保存到数据库
        save(user);
        return user;
    }
}
