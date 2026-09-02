package asia.creat.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.dto.PasswordLoginDTO;
import asia.creat.dto.SetPasswordDTO;
import asia.creat.dto.UserDTO;
import asia.creat.dto.UserLoginDTO;
import asia.creat.dto.UserProfileUpdateDTO;
import asia.creat.entity.User;
import asia.creat.entity.UserInfo;
import asia.creat.mapper.UserMapper;
import asia.creat.service.UserInfoService;
import asia.creat.service.UserService;
import asia.creat.utils.PasswordEncoder;
import asia.creat.utils.RegexUtils;
import asia.creat.utils.UserHolder;
import asia.creat.vo.UserVO;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static asia.creat.utils.RedisConstants.LOGIN_CODE_COOLDOWN;
import static asia.creat.utils.RedisConstants.LOGIN_CODE_COOLDOWN_KEY;
import static asia.creat.utils.RedisConstants.LOGIN_CODE_DAILY_LIMIT;
import static asia.creat.utils.RedisConstants.LOGIN_CODE_KEY;
import static asia.creat.utils.RedisConstants.LOGIN_CODE_QUOTA_KEY;
import static asia.creat.utils.RedisConstants.LOGIN_CODE_QUOTA_TTL;
import static asia.creat.utils.RedisConstants.LOGIN_CODE_TTL;
import static asia.creat.utils.RedisConstants.LOGIN_USER_KEY;
import static asia.creat.utils.RedisConstants.LOGIN_USER_TTL;
import static asia.creat.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private static final DefaultRedisScript<Long> CONSUME_LOGIN_CODE_SCRIPT = new DefaultRedisScript<>();

    static {
        CONSUME_LOGIN_CODE_SCRIPT.setLocation(new ClassPathResource("lua/consume_login_code.lua"));
        CONSUME_LOGIN_CODE_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final UserInfoService userInfoService;

    @Override
    public void sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException("手机号格式错误！");
        }

        // 先获取冷却发送权，避免高频请求覆盖有效验证码。
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOGIN_CODE_COOLDOWN_KEY + phone, "1", LOGIN_CODE_COOLDOWN);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException("验证码发送过于频繁，请稍后再试");
        }

        // 单日发送上限。
        String quotaKey = LOGIN_CODE_QUOTA_KEY + phone;
        Long sentToday = stringRedisTemplate.opsForValue().increment(quotaKey);
        if (sentToday != null && sentToday == 1L) {
            // 仅首次计数时设置过期，保持固定统计窗口。
            stringRedisTemplate.expire(quotaKey, LOGIN_CODE_QUOTA_TTL);
        }
        if (sentToday != null && sentToday > LOGIN_CODE_DAILY_LIMIT) {
            throw new BusinessException("今日验证码发送次数已达上限，请明天再试");
        }

        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL);
        log.debug("验证码已生成并写入 Redis");
    }

    @Override
    public String login(UserLoginDTO loginDTO) {
        String phone = loginDTO.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException("手机号格式错误！");
        }

        Long consumed = stringRedisTemplate.execute(
                CONSUME_LOGIN_CODE_SCRIPT,
                Collections.singletonList(LOGIN_CODE_KEY + phone),
                loginDTO.getCode());
        if (consumed == null || consumed != 1L) {
            throw new BusinessException("验证码错误！");
        }

        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        return createLoginToken(user);
    }

    @Override
    public String loginByPassword(PasswordLoginDTO loginDTO) {
        String phone = loginDTO.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new BusinessException("手机号或密码错误");
        }

        User user = query().eq("phone", phone).one();
        if (user == null || !PasswordEncoder.matches(user.getPassword(), loginDTO.getPassword())) {
            throw new BusinessException("手机号或密码错误");
        }

        return createLoginToken(user);
    }

    @Override
    public void setPassword(SetPasswordDTO passwordDTO) {
        Long userId = UserHolder.getUser().getId();
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (StrUtil.isNotEmpty(user.getPassword())) {
            throw new BusinessException("密码已设置，不能重复设置");
        }

        boolean updated = lambdaUpdate()
                .set(User::getPassword, PasswordEncoder.encode(passwordDTO.getPassword()))
                .eq(User::getId, userId)
                .and(wrapper -> wrapper.isNull(User::getPassword).or().eq(User::getPassword, ""))
                .update();
        if (!updated) {
            throw new BusinessException("密码已设置，不能重复设置");
        }
    }

    @Override
    public boolean hasPassword() {
        Long userId = UserHolder.getUser().getId();
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        return StrUtil.isNotEmpty(user.getPassword());
    }

    private String createLoginToken(User user) {
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL);
        return token;
    }

    @Override
    public void logout(String token) {
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
    }

    @Override
    public UserVO queryUserById(Long userId) {
        User user = getById(userId);
        return user == null ? null : BeanUtil.copyProperties(user, UserVO.class);
    }

    @Override
    public UserVO getCurrentUser() {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return null;
        }
        User dbUser = getById(user.getId());
        return dbUser == null ? BeanUtil.copyProperties(user, UserVO.class) : BeanUtil.copyProperties(dbUser, UserVO.class);
    }

    @Override
    public List<UserVO> queryUsersByIdsSorted(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<User> users = listByIds(ids);
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (k1, k2) -> k1));
        return ids.stream()
                .map(userMap::get)
                .filter(Objects::nonNull)
                .map(user -> BeanUtil.copyProperties(user, UserVO.class))
                .toList();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);

        // 初始化用户详情
        UserInfo info = new UserInfo()
                .setUserId(user.getId())
                .setCredits(0);
        userInfoService.save(info);
        return user;
    }

    @Override
    public void updateProfile(UserProfileUpdateDTO updateDTO, String token) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException("用户未登录");
        }
        if (updateDTO == null) {
            return;
        }
        Long userId = currentUser.getId();

        // 尝试从 RequestContext 中获取 token
        if (StrUtil.isBlank(token)) {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                token = attributes.getRequest().getHeader("authorization");
            }
        }

        // 1. 更新 tb_user (nickName, icon)
        boolean needUpdateUser = false;
        LambdaUpdateWrapper<User> userUpdateWrapper =
                new LambdaUpdateWrapper<>();
        userUpdateWrapper.eq(User::getId, userId);

        if (StrUtil.isNotBlank(updateDTO.getNickName())) {
            userUpdateWrapper.set(User::getNickName, updateDTO.getNickName().trim());
            needUpdateUser = true;
        }
        if (StrUtil.isNotBlank(updateDTO.getIcon())) {
            userUpdateWrapper.set(User::getIcon, updateDTO.getIcon().trim());
            needUpdateUser = true;
        }

        if (needUpdateUser) {
            update(userUpdateWrapper);
            // 同步更新 UserHolder 内存对象
            if (StrUtil.isNotBlank(updateDTO.getNickName())) {
                currentUser.setNickName(updateDTO.getNickName().trim());
            }
            if (StrUtil.isNotBlank(updateDTO.getIcon())) {
                currentUser.setIcon(updateDTO.getIcon().trim());
            }
            // 同步刷新 Redis 会话 Hash 缓存
            if (StrUtil.isNotBlank(token)) {
                String tokenKey = LOGIN_USER_KEY + token;
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(tokenKey))) {
                    if (StrUtil.isNotBlank(updateDTO.getNickName())) {
                        stringRedisTemplate.opsForHash().put(tokenKey, "nickName", updateDTO.getNickName().trim());
                    }
                    if (StrUtil.isNotBlank(updateDTO.getIcon())) {
                        stringRedisTemplate.opsForHash().put(tokenKey, "icon", updateDTO.getIcon().trim());
                    }
                }
            }
        }

        // 2. 更新 tb_user_info (city, introduce, gender, birthday)
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            info = new UserInfo()
                    .setUserId(userId)
                    .setCity(updateDTO.getCity())
                    .setIntroduce(updateDTO.getIntroduce())
                    .setGender(updateDTO.getGender())
                    .setBirthday(updateDTO.getBirthday())
                    .setCredits(0);
            userInfoService.save(info);
        } else {
            LambdaUpdateWrapper<UserInfo> infoWrapper =
                    new LambdaUpdateWrapper<>();
            infoWrapper.eq(UserInfo::getUserId, userId);

            boolean needUpdateInfo = false;
            if (updateDTO.getCity() != null) {
                infoWrapper.set(UserInfo::getCity, updateDTO.getCity());
                needUpdateInfo = true;
            }
            if (updateDTO.getIntroduce() != null) {
                infoWrapper.set(UserInfo::getIntroduce, updateDTO.getIntroduce());
                needUpdateInfo = true;
            }
            if (updateDTO.getGender() != null) {
                infoWrapper.set(UserInfo::getGender, updateDTO.getGender());
                needUpdateInfo = true;
            }
            if (updateDTO.getBirthday() != null) {
                infoWrapper.set(UserInfo::getBirthday, updateDTO.getBirthday());
                needUpdateInfo = true;
            }

            if (needUpdateInfo) {
                userInfoService.update(infoWrapper);
            }
        }
    }
}
