package asia.creat.service;

import asia.creat.dto.EventCreateDTO;
import asia.creat.dto.EventQueryDTO;
import asia.creat.dto.EventUpdateDTO;
import asia.creat.dto.NearbyEventQueryDTO;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Event;
import asia.creat.vo.EventDetailVO;
import asia.creat.vo.EventListItemVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface EventService extends IService<Event> {

    EventDetailVO queryById(Long id);

    Long createEvent(EventCreateDTO createDTO);

    void updateEvent(EventUpdateDTO updateDTO);

    List<EventListItemVO> queryByCategory(EventQueryDTO query);

    /** 查询全部分类中指定半径内的活动。 */
    List<EventListItemVO> queryNearby(NearbyEventQueryDTO query);

    List<EventListItemVO> queryHotEvents(PageQuery query);

    /** 标记"想看"（HyperLogLog UV），返回当前想看人数 */
    /**
     * @param visitorIp 未登录访客的去重标识，由控制层从请求中取得
     */
    long addUv(Long eventId, String visitorIp);

    /** 查询某演出的想看人数（UV） */
    long queryUv(Long eventId);
}
