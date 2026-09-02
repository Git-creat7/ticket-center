package asia.creat.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.dto.EventCreateDTO;
import asia.creat.dto.EventQueryDTO;
import asia.creat.dto.EventUpdateDTO;
import asia.creat.dto.NearbyEventQueryDTO;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Event;
import asia.creat.entity.EventCategory;
import asia.creat.entity.Ticket;
import asia.creat.mapper.EventMapper;
import asia.creat.mapper.TicketMapper;
import asia.creat.service.EventCategoryService;
import asia.creat.service.EventService;
import asia.creat.utils.CacheClient;
import asia.creat.utils.UserHolder;
import asia.creat.vo.EventDetailVO;
import asia.creat.vo.EventListItemVO;
import asia.creat.vo.TicketVO;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static asia.creat.utils.RedisConstants.CACHE_EVENT_DETAIL_KEY;
import static asia.creat.utils.RedisConstants.CACHE_EVENT_DETAIL_TTL;
import static asia.creat.utils.RedisConstants.EVENT_GEO_KEY;
import static asia.creat.utils.RedisConstants.EVENT_GEO_ALL_KEY;
import static asia.creat.utils.RedisConstants.LOCK_EVENT_GEO_ALL_KEY;
import static asia.creat.utils.RedisConstants.LOCK_EVENT_TTL;
import static asia.creat.utils.RedisConstants.LOCK_EVENT_TYPE_KEY;
import static asia.creat.utils.RedisConstants.UV_EVENT_KEY;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventServiceImpl extends ServiceImpl<EventMapper, Event> implements EventService {

    private final CacheClient cacheClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final EventCategoryService eventCategoryService;
    private final TicketMapper ticketMapper;

    @Override
    public EventDetailVO queryById(Long id) {
        EventDetailVO vo = cacheClient.queryWithPassThrough(
                CACHE_EVENT_DETAIL_KEY, id, EventDetailVO.class, this::loadEventDetailVOFromDb, CACHE_EVENT_DETAIL_TTL
        );
        if (vo == null) {
            throw new BusinessException(404, "演出不存在");
        }
        return vo;
    }

    private EventDetailVO loadEventDetailVOFromDb(Long id) {
        Event event = getById(id);
        if (event == null) {
            return null;
        }

        EventDetailVO vo = BeanUtil.copyProperties(event, EventDetailVO.class);

        // 关联分类名称：走带缓存的分类全量表，见 categoryNameMap()
        vo.setCategoryName(categoryNameMap().get(event.getCategoryId()));

        // 关联票档列表
        List<Ticket> tickets = ticketMapper.queryTicketOfEvent(id);
        if (tickets != null && !tickets.isEmpty()) {
            vo.setTickets(tickets.stream().map(t -> BeanUtil.copyProperties(t, TicketVO.class)).toList());
        }

        return vo;
    }

    @Override
    public Long createEvent(EventCreateDTO createDTO) {
        Event event = BeanUtil.copyProperties(createDTO, Event.class);
        event.setStatus(1);
        event.setHot(0);
        event.setComments(0);
        save(event);
        stringRedisTemplate.delete(EVENT_GEO_ALL_KEY);
        stringRedisTemplate.delete(EVENT_GEO_KEY + event.getCategoryId());
        return event.getId();
    }

    @Override
    public void updateEvent(EventUpdateDTO updateDTO) {
        Long id = updateDTO.getId();
        Event existing = getById(id);
        if (existing == null) {
            throw new BusinessException(404, "演出不存在");
        }

        Event event = BeanUtil.copyProperties(updateDTO, Event.class);
        updateById(event);
        stringRedisTemplate.delete(CACHE_EVENT_DETAIL_KEY + id);
        stringRedisTemplate.delete(EVENT_GEO_ALL_KEY);
        stringRedisTemplate.delete(EVENT_GEO_KEY + existing.getCategoryId());
        if (updateDTO.getCategoryId() != null
                && !Objects.equals(updateDTO.getCategoryId(), existing.getCategoryId())) {
            stringRedisTemplate.delete(EVENT_GEO_KEY + updateDTO.getCategoryId());
        }
    }

    @Override
    public List<EventListItemVO> queryHotEvents(PageQuery query) {
        // 必须带第二排序键：hot 相同的行在 MySQL 里顺序不保证，
        // 翻页时同一行可能出现在两页或被跳过
        Page<Event> page = query()
                .eq("status", 1)
                .orderByDesc("hot")
                .orderByDesc("id")
                .page(query.toPage());

        return toListItemVOList(page.getRecords());
    }

    @Override
    public long addUv(Long eventId, String visitorIp) {
        // 登录用户使用 id，匿名用户使用来源 IP，避免随机标识造成重复计数。
        // 代理地址由 Spring 的可信代理配置处理，不直接读取可伪造的请求头。
        String visitor;
        if (UserHolder.getUser() != null) {
            visitor = String.valueOf(UserHolder.getUser().getId());
        } else if (StrUtil.isNotBlank(visitorIp)) {
            // 加前缀，避免 IP 字面量和登录用户 id 撞上
            visitor = "ip:" + visitorIp;
        } else {
            // 取不到来源地址就不计数：宁可漏一次，也不引入可被刷的随机标识
            log.warn("无法获取访客来源地址，跳过 UV 计数, eventId={}", eventId);
            return queryUv(eventId);
        }
        stringRedisTemplate.opsForHyperLogLog().add(UV_EVENT_KEY + eventId, visitor);
        return queryUv(eventId);
    }

    @Override
    public long queryUv(Long eventId) {
        Long uv = stringRedisTemplate.opsForHyperLogLog().size(UV_EVENT_KEY + eventId);
        return uv == null ? 0L : uv;
    }

    @Override
    public List<EventListItemVO> queryByCategory(EventQueryDTO query) {
        Integer categoryId = query.getCategoryId();
        Double x = query.getX();
        Double y = query.getY();

        if (x == null || y == null) {
            Page<Event> page = query()
                    .eq("category_id", categoryId)
                    .eq("status", 1)
                    .orderByDesc("hot")
                    // 与 queryHotEvents 同理：hot 相同的行顺序不保证，缺第二排序键会漏行/重行
                    .orderByDesc("id")
                    .page(query.toPage());
            return toListItemVOList(page.getRecords());
        }

        String key = EVENT_GEO_KEY + categoryId;
        loadEventsIntoGeo(key, LOCK_EVENT_TYPE_KEY + categoryId, categoryId);
        return queryByGeo(key, x, y, query.getRadius(), query.getCurrent(), query.getSize());
    }

    @Override
    public List<EventListItemVO> queryNearby(NearbyEventQueryDTO query) {
        loadEventsIntoGeo(EVENT_GEO_ALL_KEY, LOCK_EVENT_GEO_ALL_KEY, null);
        return queryByGeo(
                EVENT_GEO_ALL_KEY,
                query.getX(),
                query.getY(),
                query.getRadius(),
                query.getCurrent(),
                query.getSize()
        );
    }

    private List<EventListItemVO> queryByGeo(
            String key, double x, double y, int radius, long current, long size) {
        int from = (int) ((current - 1) * size);
        int end = (int) (current * size);

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(radius, Metrics.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs
                                .newGeoSearchArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(end)
                );

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content =
                results == null ? Collections.emptyList() : results.getContent();
        if (content.isEmpty() || from >= content.size()) {
            return Collections.emptyList();
        }

        List<Long> ids = new ArrayList<>(content.size() - from);
        Map<String, Distance> distanceMap = new HashMap<>(content.size() - from);
        for (int i = from; i < content.size(); i++) {
            GeoResult<RedisGeoCommands.GeoLocation<String>> result = content.get(i);
            String eventId = result.getContent().getName();
            ids.add(Long.valueOf(eventId));
            distanceMap.put(eventId, result.getDistance());
        }

        Map<Long, Event> eventMap = listByIds(ids).stream()
                .filter(event -> event.getStatus() != null && event.getStatus() == 1)
                .collect(Collectors.toMap(Event::getId, Function.identity(), (first, second) -> first));
        List<Event> events = ids.stream()
                .map(eventMap::get)
                .filter(Objects::nonNull)
                .toList();

        List<EventListItemVO> voList = toListItemVOList(events);
        for (EventListItemVO vo : voList) {
            Distance distance = distanceMap.get(vo.getId().toString());
            if (distance != null) {
                vo.setDistance(distance.getValue());
            }
        }
        return voList;
    }

    private List<EventListItemVO> toListItemVOList(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> categoryNames = categoryNameMap();
        return events.stream().map(event -> {
            EventListItemVO vo = BeanUtil.copyProperties(event, EventListItemVO.class);
            vo.setCategoryName(categoryNames.get(event.getCategoryId()));
            return vo;
        }).toList();
    }

    /** 复用分类列表缓存，批量构造 id 到名称的映射。 */
    private Map<Long, String> categoryNameMap() {
        List<EventCategory> categories = eventCategoryService.queryCategoryList();
        if (categories.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> map = new HashMap<>(categories.size());
        for (EventCategory category : categories) {
            map.put(category.getId(), category.getName());
        }
        return map;
    }

    /** 按需加载上架且有坐标的演出，categoryId 为空时加载全部分类。 */
    private void loadEventsIntoGeo(String key, String lockKey, Integer categoryId) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }

        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_EVENT_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                return;
            }
            List<Event> events = query()
                    .eq(categoryId != null, "category_id", categoryId)
                    .eq("status", 1)
                    .isNotNull("x")
                    .isNotNull("y")
                    .list();
            if (events.isEmpty()) {
                return;
            }
            Map<String, Point> points = new HashMap<>(events.size());
            for (Event event : events) {
                points.put(event.getId().toString(), new Point(event.getX(), event.getY()));
            }
            stringRedisTemplate.opsForGeo().add(key, points);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }
}
