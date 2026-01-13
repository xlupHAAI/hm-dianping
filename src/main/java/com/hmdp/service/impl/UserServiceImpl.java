package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICKNAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        stringRedisTemplate.opsForValue().set(
                LOGIN_VERICODE_PREFIX + phone,
                RandomUtil.randomNumbers(6),
                LOGIN_VERICODE_TTL, TimeUnit.MINUTES
        );

        log.debug("usr: " + phone + ", state: login, 已发送验证码。");
        return Result.ok();
    }

    public User fetchUsrAccount(String phone) {
        User usr = query().eq("phone", phone).one();
        if (usr == null) {  // 注册新用户
            usr = new User();
            usr.setPhone(phone);
            usr.setNickName(USER_NICKNAME_PREFIX + RandomUtil.randomString(10));
            //usr.setCreateTime(LocalDateTime.now());
            save(usr);
        }
        return usr;
    }

    /**
     * 输入的手机号需与后端 cache 的验证码匹配
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        String veriCode = stringRedisTemplate.opsForValue().get(LOGIN_VERICODE_PREFIX + phone);

        if (veriCode == null || !veriCode.equals(loginForm.getCode())) {
            return Result.fail("验证码与手机号不匹配！");
        }

        User usr = fetchUsrAccount(phone);
        UserDTO usrDTO = BeanUtil.copyProperties(usr, UserDTO.class);
        String token = UUID.randomUUID().toString(true);

        stringRedisTemplate.opsForHash().putAll(
                LOGIN_TOKEN_PREFIX + token,
                BeanUtil.beanToMap(
                        usrDTO,
                        new HashMap<>(),
                        CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor(
                                (fieldName, fieldVal) -> fieldVal.toString()
                        ))
        );
        stringRedisTemplate.expire(
                LOGIN_TOKEN_PREFIX + token,
                LOGIN_TOKEN_TTL, TimeUnit.MINUTES
        );

        return Result.ok(token);
    }


}
