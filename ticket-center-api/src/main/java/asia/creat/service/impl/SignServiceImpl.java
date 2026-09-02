package asia.creat.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.entity.CreditLog;
import asia.creat.entity.UserInfo;
import asia.creat.service.CreditLogService;
import asia.creat.service.SignService;
import asia.creat.service.UserInfoService;
import asia.creat.utils.UserHolder;
import asia.creat.vo.SignDayVO;
import asia.creat.vo.SignStatusVO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static asia.creat.utils.RedisConstants.LOCK_SIGN_KEY;
import static asia.creat.utils.RedisConstants.LOCK_SIGN_WAIT;
import static asia.creat.utils.RedisConstants.USER_SIGN_KEY;

@Slf4j
@Service
@RequiredArgsConstructor
public class SignServiceImpl implements SignService {

    private static final DateTimeFormatter SIGN_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final byte[] EMPTY_BITMAP = new byte[0];

    private final StringRedisTemplate stringRedisTemplate;
    private final UserInfoService userInfoService;
    private final CreditLogService creditLogService;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String signDate = now.toLocalDate().toString();
        RLock lock = redissonClient.getLock(LOCK_SIGN_KEY + userId + ":" + signDate);

        boolean locked;
        try {
            locked = lock.tryLock(LOCK_SIGN_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("签到处理中断，请稍后重试", e);
        }
        if (!locked) {
            throw new BusinessException("签到请求处理中，请稍后重试");
        }

        try {
            Integer currentBalance = transactionTemplate.execute(status -> grantSignCredits(userId, signDate, now));
            if (currentBalance != null) {
                log.info("用户打卡成功并增加 10 积分, userId={}, 变动后余额={}", userId, currentBalance);
            }

            String dayKey = USER_SIGN_KEY + userId + ":" + now.format(SIGN_MONTH_FORMATTER);
            stringRedisTemplate.opsForValue().setBit(dayKey, now.getDayOfMonth() - 1, true);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Integer grantSignCredits(Long userId, String signDate, LocalDateTime now) {
        UserInfo info = userInfoService.lambdaQuery()
                .eq(UserInfo::getUserId, userId)
                .last("FOR UPDATE")
                .one();
        boolean rewarded = creditLogService.lambdaQuery()
                .eq(CreditLog::getUserId, userId)
                .eq(CreditLog::getBizType, 1)
                .eq(CreditLog::getBizId, signDate)
                .count() > 0;
        if (rewarded) {
            return null;
        }

        if (info == null) {
            info = new UserInfo()
                    .setUserId(userId)
                    .setCredits(10);
            if (!userInfoService.save(info)) {
                throw new BusinessException("签到积分发放失败");
            }
        } else {
            boolean updated = userInfoService.update(new LambdaUpdateWrapper<UserInfo>()
                    .setSql("credits = credits + 10")
                    .eq(UserInfo::getUserId, userId));
            if (!updated) {
                throw new BusinessException("签到积分发放失败");
            }
        }

        UserInfo updated = userInfoService.getById(userId);
        int currentBalance = updated == null || updated.getCredits() == null ? 10 : updated.getCredits();
        CreditLog creditLog = CreditLog.builder()
                .userId(userId)
                .bizType(1)
                .bizId(signDate)
                .changeAmount(10)
                .balance(currentBalance)
                .description("每日打卡签到奖励")
                .createTime(now)
                .build();
        if (!creditLogService.save(creditLog)) {
            throw new BusinessException("签到积分流水记录失败");
        }
        return currentBalance;
    }

    /** 今天未签到时，连续签到统计到昨天。 */
    private static LocalDate streakEndDay(LocalDate today, boolean isTodaySigned) {
        return isTodaySigned ? today : today.minusDays(1);
    }

    /** 按月读取位图，向前统计可跨月的连续签到天数。 */
    private int continuousSignDays(Long userId, LocalDate lastDay, Map<String, byte[]> bitmaps) {
        int streak = 0;
        LocalDate cursor = lastDay;

        while (true) {
            byte[] bitmap = monthBitmap(userId, cursor, bitmaps);
            int day = cursor.getDayOfMonth();
            while (day >= 1 && isSigned(bitmap, day)) {
                streak++;
                day--;
            }
            if (day >= 1) {
                return streak;
            }

            // 本月签到连续到 1 号时，继续检查上月。
            cursor = cursor.withDayOfMonth(1).minusDays(1);
        }
    }

    /** 读取整月原始位图；二进制值不能经过 String 序列化。 */
    private byte[] monthBitmap(Long userId, LocalDate date, Map<String, byte[]> bitmaps) {
        String key = USER_SIGN_KEY + userId + ":" + date.format(SIGN_MONTH_FORMATTER);
        return bitmaps.computeIfAbsent(key, k -> {
            byte[] raw = stringRedisTemplate.execute((RedisCallback<byte[]>) connection ->
                    connection.stringCommands().get(k.getBytes(StandardCharsets.UTF_8))
            );
            return raw == null ? EMPTY_BITMAP : raw;
        });
    }

    /** Redis 位图从字节最高位开始计数，第 N 天对应 offset N-1。 */
    private static boolean isSigned(byte[] bitmap, int dayOfMonth) {
        int offset = dayOfMonth - 1;
        int index = offset / 8;
        if (index >= bitmap.length) {
            return false;
        }
        return (bitmap[index] & (0x80 >>> (offset % 8))) != 0;
    }

    private static int countSignedDays(byte[] bitmap) {
        int count = 0;
        for (byte b : bitmap) {
            count += Integer.bitCount(b & 0xFF);
        }
        return count;
    }

    @Override
    public SignStatusVO getSignStatus() {
        Long userId = UserHolder.getUser().getId();
        LocalDate today = LocalDate.now();
        Map<String, byte[]> bitmaps = new HashMap<>(2);
        byte[] thisMonth = monthBitmap(userId, today, bitmaps);

        boolean isTodaySigned = isSigned(thisMonth, today.getDayOfMonth());
        int continuousDays = continuousSignDays(userId, streakEndDay(today, isTodaySigned), bitmaps);
        int totalDays = countSignedDays(thisMonth);

        LocalDate monday = today.with(DayOfWeek.MONDAY);
        String[] dayNames = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        List<SignDayVO> weekDays = new ArrayList<>(7);

        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            boolean isToday = date.equals(today);
            boolean isFuture = date.isAfter(today);
            boolean isSigned = !isFuture
                    && isSigned(monthBitmap(userId, date, bitmaps), date.getDayOfMonth());

            weekDays.add(SignDayVO.builder()
                    .dayName(dayNames[i])
                    .date(date.toString())
                    .dayOfMonth(date.getDayOfMonth())
                    .isSigned(isSigned)
                    .isToday(isToday)
                    .isFuture(isFuture)
                    .build());
        }

        return SignStatusVO.builder()
                .isTodaySigned(isTodaySigned)
                .continuousDays(continuousDays)
                .monthlyTotalDays(totalDays)
                .currentMonth(today.getMonthValue())
                .weekDays(weekDays)
                .build();
    }
}
