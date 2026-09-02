package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Follow;
import asia.creat.vo.UserVO;
import com.baomidou.mybatisplus.extension.service.IService;

public interface FollowService extends IService<Follow> {

    void follow(Long targetUserId, boolean isFollow);

    boolean isFollow(Long targetUserId);

    PageResult<UserVO> queryFollowees(Long userId, PageQuery query);

    PageResult<UserVO> queryFans(Long userId, PageQuery query);

    int countFollowee(Long userId);

    int countFans(Long userId);
}
