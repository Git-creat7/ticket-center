package asia.creat.service;

import asia.creat.entity.EventCategory;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface EventCategoryService extends IService<EventCategory> {

    List<EventCategory> queryCategoryList();
}
