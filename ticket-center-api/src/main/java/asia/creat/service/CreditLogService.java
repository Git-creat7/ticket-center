package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.PageQuery;
import asia.creat.entity.CreditLog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface CreditLogService extends IService<CreditLog> {

    /** 记录积分流水 */
    void recordLog(Long userId, Integer bizType, String bizId, Integer changeAmount, Integer balance, String description);

    /** 查询用户积分明细流水 */
    PageResult<CreditLog> queryUserCreditLogs(Long userId, PageQuery query);
}
