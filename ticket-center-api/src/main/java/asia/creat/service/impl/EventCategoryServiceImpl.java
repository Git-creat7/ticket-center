package asia.creat.service.impl;

import asia.creat.entity.EventCategory;
import asia.creat.mapper.EventCategoryMapper;
import asia.creat.service.EventCategoryService;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

import static asia.creat.utils.RedisConstants.CACHE_EVENT_CATEGORY_KEY;
import static asia.creat.utils.RedisConstants.CACHE_EVENT_CATEGORY_TTL;
import static asia.creat.utils.RedisConstants.CACHE_NULL_TTL;

@Service
@RequiredArgsConstructor
public class EventCategoryServiceImpl extends ServiceImpl<EventCategoryMapper, EventCategory> implements EventCategoryService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public List<EventCategory> queryCategoryList() {
        String json = stringRedisTemplate.opsForValue().get(CACHE_EVENT_CATEGORY_KEY);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toList(json, EventCategory.class);
        }
        if (json != null) {
            return Collections.emptyList();
        }

        List<EventCategory> list = query().orderByAsc("sort").list();
        if (list == null || list.isEmpty()) {
            stringRedisTemplate.opsForValue().set(CACHE_EVENT_CATEGORY_KEY, "", CACHE_NULL_TTL);
            return Collections.emptyList();
        }
        stringRedisTemplate.opsForValue().set(
                CACHE_EVENT_CATEGORY_KEY,
                JSONUtil.toJsonStr(list),
                CACHE_EVENT_CATEGORY_TTL
        );
        return list;
    }
}
