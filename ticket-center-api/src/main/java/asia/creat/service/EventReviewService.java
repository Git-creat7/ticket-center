package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.EventReviewCreateDTO;
import asia.creat.dto.PageQuery;
import asia.creat.dto.ScrollQueryDTO;
import asia.creat.entity.EventReview;
import asia.creat.vo.EventReviewVO;
import asia.creat.vo.ScrollResultVO;
import asia.creat.vo.UserVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface EventReviewService extends IService<EventReview> {

    List<EventReviewVO> queryHotReview(PageQuery query);

    PageResult<EventReviewVO> queryReviewsByUser(Long userId, PageQuery query);

    EventReviewVO queryReviewById(Long id);

    void likeReview(Long id);

    List<UserVO> queryReviewLikes(Long id);

    Long saveReview(EventReviewCreateDTO createDTO);

    void deleteReview(Long id);

    ScrollResultVO<EventReviewVO> queryReviewOfFollow(ScrollQueryDTO queryDTO);
}
