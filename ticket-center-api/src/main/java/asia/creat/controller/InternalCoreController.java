package asia.creat.controller;

import asia.creat.dto.EventSummaryDTO;
import asia.creat.entity.Event;
import asia.creat.entity.User;
import asia.creat.mapper.EventMapper;
import asia.creat.mapper.UserMapper;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal")
public class InternalCoreController {

    private final EventMapper eventMapper;
    private final UserMapper userMapper;

    @GetMapping("/events/{eventId}")
    public ResponseEntity<EventSummaryDTO> getEvent(@PathVariable Long eventId) {
        Event event = eventMapper.selectById(eventId);
        return event == null ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(BeanUtil.copyProperties(event, EventSummaryDTO.class));
    }

    @GetMapping("/users/{userId}/admin")
    public boolean isAdmin(@PathVariable Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && Integer.valueOf(1).equals(user.getRole());
    }
}
