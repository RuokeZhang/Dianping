package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

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

        //4. save the code to the session
        session.setAttribute("code",code);
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
        Object cachedCode=session.getAttribute("code");
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
        //8. 保存用户信息到session中
        session.setAttribute("user",user);
        return Result.ok();
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
