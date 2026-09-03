package asia.creat.sign;

import asia.creat.dto.UserDTO;
import asia.creat.service.SignService;
import asia.creat.support.IntegrationTestcontainers;
import asia.creat.utils.RedisConstants;
import asia.creat.utils.UserHolder;
import asia.creat.vo.SignDayVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

// 验证连续签到天数跨月回溯。
@SpringBootTest
public class SignStreakTest extends IntegrationTestcontainers {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /** 专用测试用户，避免污染真实数据 */
    private static final Long USER_ID = 88821L;

    @Autowired
    private SignService signService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        UserHolder.saveUser(user);
        clearBitmaps();
    }

    @AfterEach
    void tearDown() {
        clearBitmaps();
        UserHolder.removeUser();
    }

    @Test
    @DisplayName("1. 上月整月 + 本月至今全部签到：连续天数应跨月累加，而非只算本月")
    void testStreakSpansMonthBoundary() {
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);
        int daysInLastMonth = lastMonthEnd.getDayOfMonth();
        int daysThisMonth = today.getDayOfMonth();

        signRange(today, 1, daysThisMonth);
        signRange(lastMonthEnd, 1, daysInLastMonth);

        int expected = daysThisMonth + daysInLastMonth;
        int actual = signService.getSignStatus().getContinuousDays();

        Assertions.assertEquals(expected, actual,
                "连续签到应跨月回溯：本月 " + daysThisMonth + " 天 + 上月 " + daysInLastMonth
                        + " 天 = " + expected + " 天，实际 " + actual
                        + "。只等于本月天数说明没有回溯上个月");
    }

    @Test
    @DisplayName("2. 上月最后一天缺签：连续天数应停在本月 1 号，不越过断点")
    void testStreakStopsAtGapInPreviousMonth() {
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);
        int daysInLastMonth = lastMonthEnd.getDayOfMonth();
        int daysThisMonth = today.getDayOfMonth();

        signRange(today, 1, daysThisMonth);
        // 上月除最后一天外全部签到，制造一个正好落在月份边界上的断点
        if (daysInLastMonth > 1) {
            signRange(lastMonthEnd, 1, daysInLastMonth - 1);
        }

        int actual = signService.getSignStatus().getContinuousDays();

        Assertions.assertEquals(daysThisMonth, actual,
                "上月最后一天未签到，连续记录应止于本月 1 号，期望 " + daysThisMonth
                        + " 天，实际 " + actual + "。数值偏大说明跨过了断点继续累加");
    }

    @Test
    @DisplayName("3. 今日未签到但昨天签过：连续记录未断，天数应保留而不是归零")
    void testTodayNotSignedKeepsStreak() {
        int expected = signThroughYesterday();

        int actual = signService.getSignStatus().getContinuousDays();

        Assertions.assertEquals(expected, actual,
                "一天还没过完，今日未签不代表连续中断，期望 " + expected + " 天，实际 " + actual
                        + "。返回 0 说明把统计终点错设成了今天");
    }

    @Test
    @DisplayName("4. 昨天也未签到：连续记录真正中断，天数为 0")
    void testYesterdayNotSignedYieldsZero() {
        // 签到到前天为止，昨天与今天都留空（前天可能落在上个月）
        LocalDate dayBeforeYesterday = today.minusDays(2);
        signRange(dayBeforeYesterday, 1, dayBeforeYesterday.getDayOfMonth());

        Assertions.assertEquals(0, signService.getSignStatus().getContinuousDays(),
                "昨天未签到，连续记录应中断为 0");
    }

    @Test
    @DisplayName("6. 本周每日状态：已签的置 true、未签的置 false、今天之后标记为 future")
    void testWeekDaysReflectBitmap() {
        LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
        // 只签本周一与今天，本周其余已过去的日子留空，用来区分"未签"与"未来"
        signDay(monday);
        signDay(today);

        List<SignDayVO> weekDays = signService.getSignStatus().getWeekDays();

        Assertions.assertEquals(7, weekDays.size(), "本周状态应固定返回周一至周日 7 天");
        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            SignDayVO vo = weekDays.get(i);

            Assertions.assertEquals(date.toString(), vo.getDate(), "第 " + (i + 1) + " 项日期错位");
            Assertions.assertEquals(date.isAfter(today), vo.getIsFuture(),
                    date + " 的 isFuture 判定错误");
            Assertions.assertEquals(date.equals(today), vo.getIsToday(),
                    date + " 的 isToday 判定错误");

            boolean expectedSigned = !date.isAfter(today)
                    && (date.equals(monday) || date.equals(today));
            Assertions.assertEquals(expectedSigned, vo.getIsSigned(),
                    date + " 的签到状态错误：只签了本周一与今天，其余应为 false");
        }
    }

    @Test
    @DisplayName("7. 当月累计天数：等于本月位图里置 1 的位数，与是否连续无关")
    void testMonthlyTotalCountsScatteredDays() {
        // 故意签成不连续的：1 号、今天，中间断开
        signDay(today.withDayOfMonth(1));
        int expected = 1;
        if (today.getDayOfMonth() > 2) {
            signDay(today);
            expected = 2;
        }

        Assertions.assertEquals(expected, signService.getSignStatus().getMonthlyTotalDays(),
                "当月累计应只数本月置 1 的位数，期望 " + expected + " 天");
    }

    @Test
    @DisplayName("9. 位图短于要查的那一天：越界的位应算未签到，不能读出脏数据")
    void testDayBeyondBitmapLengthIsUnsigned() {
        // 只签到 1 号时位图覆盖 1~8 号，用于验证越过位图末尾的日期按未签到处理。
        org.junit.jupiter.api.Assumptions.assumeTrue(today.getDayOfMonth() >= 9,
                "今天在本月前 8 天内，位图本就覆盖了今天，越界分支测不到");
        LocalDate firstDay = today.withDayOfMonth(1);
        signDay(firstDay);

        var status = signService.getSignStatus();

        Assertions.assertFalse(status.getIsTodaySigned(), "只签了 1 号，今天应为未签到");
        Assertions.assertEquals(1, status.getMonthlyTotalDays(), "当月累计应只有 1 号这一天");
        for (SignDayVO vo : status.getWeekDays()) {
            Assertions.assertEquals(firstDay.toString().equals(vo.getDate()), vo.getIsSigned(),
                    vo.getDate() + " 超出位图长度，应判为未签到");
        }
    }

    @Test
    @DisplayName("8. 本周跨月时，落在上个月的那几天也要读对位图")
    void testWeekDaysAcrossMonthBoundary() {
        LocalDate monday = today.with(java.time.DayOfWeek.MONDAY);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                monday.getMonthValue() != today.getMonthValue(),
                "本周未跨月，该用例不适用（跨月周才会同时读两个月的位图）");

        signDay(monday);

        List<SignDayVO> weekDays = signService.getSignStatus().getWeekDays();

        Assertions.assertTrue(weekDays.get(0).getIsSigned(),
                "周一落在上个月，仍应从上月位图里读出已签到；为 false 说明只读了当月 key");
    }

    // 今天未签到时，从昨天开始统计。
    private int signThroughYesterday() {
        LocalDate yesterday = today.minusDays(1);
        if (today.getDayOfMonth() > 1) {
            signRange(today, 1, yesterday.getDayOfMonth());
            return yesterday.getDayOfMonth();
        }
        // 今天是 1 号：只签上月最后一天
        signRange(yesterday, yesterday.getDayOfMonth(), yesterday.getDayOfMonth());
        return 1;
    }

    // 标记指定日期。
    private void signDay(LocalDate date) {
        signRange(date, date.getDayOfMonth(), date.getDayOfMonth());
    }

    // 标记日期范围。
    private void signRange(LocalDate date, int fromDay, int toDay) {
        String key = keyOf(date);
        for (int day = fromDay; day <= toDay; day++) {
            stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        }
    }

    private void clearBitmaps() {
        stringRedisTemplate.delete(keyOf(today));
        stringRedisTemplate.delete(keyOf(today.withDayOfMonth(1).minusDays(1)));
    }

    private String keyOf(LocalDate date) {
        return RedisConstants.USER_SIGN_KEY + USER_ID + ":" + date.format(MONTH);
    }
}
