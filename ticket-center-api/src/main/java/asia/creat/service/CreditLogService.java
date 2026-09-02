package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.PageQuery;
import asia.creat.entity.CreditLog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface CreditLogService extends IService<CreditLog> {

    /**
     * 记录积分流水
     * @param userId 用户ID
     * @param bizType 业务类型: 1签到获取 2购票抵扣 3订单取消退还
     * @param bizId 业务关联ID (订单号/日期)
     * @param changeAmount 变动积分数 (+10, -500等)
     * @param balance 变动后积分余额
     * @param description 流水描述
     */
    void recordLog(Long userId, Integer bizType, String bizId, Integer changeAmount, Integer balance, String description);

    /**
     * 查询用户积分明细流水
     */
    PageResult<CreditLog> queryUserCreditLogs(Long userId, PageQuery query);
}
