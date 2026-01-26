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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if(RegexUtils.isPhoneInvalid( phone)){
            // 2.不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }


        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.保存验证码到session
        //session.setAttribute("code",code);
        // 4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code ,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.返回结果
        log.debug("发送验证码成功！验证码为：{}",code);

        return Result.ok();
    }
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误！");
        }
        String code = loginForm.getCode();
        // session获取验证码
        //Object cacheCode = session.getAttribute("code");
        // redis获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误！");
        }
        User user = this.query().eq("phone", phone).one();
        if(user == null){
            user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            this.save(user);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 保存用户登录信息到redis
        // 1.生成 uuid token
        String token = UUID.randomUUID().toString();
        String tokenKey = LOGIN_USER_KEY+token;
        // 2. 将UserDTO转换为HashMap
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .ignoreNullValue().setFieldValueEditor((fieldName, fidlValue) -> fidlValue.toString()));


        // 3. 存储
        stringRedisTemplate.opsForHash().putAll(tokenKey,stringObjectMap);
        // 4. 设置有效期
        //stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.SECONDS);
        //stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.SECONDS);

        //session.setAttribute("user",userDTO);


        return Result.ok(token);

    }

    @Override
    public Result sign() {
        return null;
    }

    @Override
    public Result signCount() {
        Long id = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY +id + keySuffix;
        // 当前是第几天
        int dayOfMonth = now.getDayOfMonth();
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if(result==null ||result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num==null||num==0){
            return Result.ok(0);
        }
        int count=0;
         // 循环遍历天数
        while(true){
            if((num & 1)==0){
                break;
            }else{
                count++;
            }
            num = num>>1;
        }
        return Result.ok(count);


    }
}
