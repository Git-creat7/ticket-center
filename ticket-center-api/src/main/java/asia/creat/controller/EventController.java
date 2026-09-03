package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.EventCreateDTO;
import asia.creat.dto.EventQueryDTO;
import asia.creat.dto.EventUpdateDTO;
import asia.creat.dto.PageQuery;
import asia.creat.dto.NearbyEventQueryDTO;
import asia.creat.service.EventService;
import asia.creat.utils.RequireAdmin;
import asia.creat.vo.EventDetailVO;
import asia.creat.vo.EventListItemVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/event")
public class EventController {

    private final EventService eventService;

    @GetMapping("/{id}")
    public Result queryEventById(@PathVariable Long id) {
        EventDetailVO vo = eventService.queryById(id);
        return Result.success(vo);
    }

    @GetMapping("/hot")
    public Result queryHotEvents(@Validated PageQuery query) {
        List<EventListItemVO> list = eventService.queryHotEvents(query);
        return Result.success(list);
    }

    @GetMapping("/of/category")
    public Result queryEventByCategory(@Validated EventQueryDTO query) {
        List<EventListItemVO> list = eventService.queryByCategory(query);
        return Result.success(list);
    }

    /** 全部分类的附近活动，按距离从近到远返回。 */
    @GetMapping("/nearby")
    public Result queryNearbyEvents(@Validated NearbyEventQueryDTO query) {
        List<EventListItemVO> list = eventService.queryNearby(query);
        return Result.success(list);
    }

    @PutMapping("/uv/{id}")
    public Result addUv(@PathVariable("id") Long eventId, HttpServletRequest request) {
        // 未登录访客按来源 IP 去重，IP 只能从请求里取，故把它传进 service
        long uv = eventService.addUv(eventId, request.getRemoteAddr());
        return Result.success(uv);
    }

    @GetMapping("/uv/{id}")
    public Result queryUv(@PathVariable("id") Long eventId) {
        long uv = eventService.queryUv(eventId);
        return Result.success(uv);
    }

    @RequireAdmin
    @PostMapping
    public Result createEvent(@RequestBody @Validated EventCreateDTO createDTO) {
        Long id = eventService.createEvent(createDTO);
        return Result.success(id);
    }

    @RequireAdmin
    @PutMapping
    public Result updateEvent(@RequestBody @Validated EventUpdateDTO updateDTO) {
        eventService.updateEvent(updateDTO);
        return Result.success();
    }
}
