package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.entity.EventCategory;
import asia.creat.service.EventCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/event-category")
public class EventCategoryController {

    private final EventCategoryService categoryService;

    @GetMapping("/list")
    public Result queryCategoryList() {
        List<EventCategory> list = categoryService.queryCategoryList();
        return Result.success(list);
    }
}
