package asia.creat.utils;

import java.time.Duration;

public class RedisConstants {
    private static final String PROJECT_KEY_PREFIX = "tc:";

    // 登录与会话
    public static final String LOGIN_CODE_KEY = PROJECT_KEY_PREFIX + "login:code:";
    public static final Duration LOGIN_CODE_TTL = Duration.ofMinutes(2);

    // 验证码发送冷却和每日次数限制。
    public static final String LOGIN_CODE_COOLDOWN_KEY = PROJECT_KEY_PREFIX + "login:code:cd:";
    public static final Duration LOGIN_CODE_COOLDOWN = Duration.ofSeconds(60);
    public static final String LOGIN_CODE_QUOTA_KEY = PROJECT_KEY_PREFIX + "login:code:quota:";
    public static final Duration LOGIN_CODE_QUOTA_TTL = Duration.ofDays(1);
    public static final long LOGIN_CODE_DAILY_LIMIT = 10;
    public static final String LOGIN_USER_KEY = PROJECT_KEY_PREFIX + "login:token:";
    public static final Duration LOGIN_USER_TTL = Duration.ofHours(10);

    public static final Duration CACHE_NULL_TTL = Duration.ofMinutes(2);

    // 演出缓存：详情页缓存聚合视图 EventDetailVO，命中时 0 次 MySQL
    public static final Duration CACHE_EVENT_DETAIL_TTL = Duration.ofMinutes(30);
    public static final String CACHE_EVENT_DETAIL_KEY = PROJECT_KEY_PREFIX + "cache:event:detail:";

    // 旧版缓存键仅供基准测试使用。
    public static final Duration CACHE_EVENT_TTL = Duration.ofMinutes(30);
    public static final String CACHE_EVENT_KEY = PROJECT_KEY_PREFIX + "cache:event:";
    public static final Duration CACHE_EVENT_CATEGORY_TTL = Duration.ofHours(1);
    public static final String CACHE_EVENT_CATEGORY_KEY = PROJECT_KEY_PREFIX + "cache:event-category";

    // 演出 GEO 与懒加载锁
    public static final String EVENT_GEO_KEY = PROJECT_KEY_PREFIX + "event:geo:";
    public static final String EVENT_GEO_ALL_KEY = PROJECT_KEY_PREFIX + "event:geo:all";
    public static final String LOCK_EVENT_TYPE_KEY = PROJECT_KEY_PREFIX + "lock:event:type:";
    public static final String LOCK_EVENT_GEO_ALL_KEY = PROJECT_KEY_PREFIX + "lock:event:geo:all";
    public static final Duration LOCK_EVENT_TTL = Duration.ofSeconds(10);

    // 票档预约：库存 / 一人一票 / 分布式锁，相关键使用相同 hash tag。
    public static final String LOCK_ORDER_KEY = PROJECT_KEY_PREFIX + "lock:order:";
    public static final String LOCK_SIGN_KEY = PROJECT_KEY_PREFIX + "lock:sign:";
    public static final String ID_ICR_KEY = PROJECT_KEY_PREFIX + "icr:";

    // 落库时等待用户订单锁的时间。
    public static final Duration LOCK_ORDER_WAIT = Duration.ofSeconds(5);
    public static final Duration LOCK_SIGN_WAIT = Duration.ofSeconds(3);

    public static String ticketStockKey(Object ticketId) {
        return PROJECT_KEY_PREFIX + "ticket:{" + ticketId + "}:stock";
    }

    public static String ticketOrderKey(Object ticketId) {
        return PROJECT_KEY_PREFIX + "ticket:{" + ticketId + "}:order";
    }

    // 订单超时（分钟）：超过该时长未支付则取消并释放库存
    public static final Duration ORDER_TIMEOUT = Duration.ofMinutes(15);

    // 评价/点赞/Feed/关注
    public static final String REVIEW_LIKED_KEY = PROJECT_KEY_PREFIX + "review:liked:";
    public static final String FEED_KEY = PROJECT_KEY_PREFIX + "feed:";

    // UV 统计（HyperLogLog）：演出"想看人数"
    public static final String UV_EVENT_KEY = PROJECT_KEY_PREFIX + "uv:event:";

    // 用户签到
    public static final String USER_SIGN_KEY = PROJECT_KEY_PREFIX + "sign:";
}
