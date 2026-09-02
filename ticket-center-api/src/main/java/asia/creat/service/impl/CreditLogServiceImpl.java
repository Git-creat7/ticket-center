package asia.creat.service.impl;

import asia.creat.common.PageResult;
import asia.creat.dto.PageQuery;
import asia.creat.entity.CreditLog;
import asia.creat.mapper.CreditLogMapper;
import asia.creat.service.CreditLogService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class CreditLogServiceImpl extends ServiceImpl<CreditLogMapper, CreditLog> implements CreditLogService {

    @Override
    public void recordLog(Long userId, Integer bizType, String bizId, Integer changeAmount, Integer balance, String description) {
        CreditLog creditLog = CreditLog.builder()
                .userId(userId)
                .bizType(bizType)
                .bizId(bizId)
                .changeAmount(changeAmount)
                .balance(balance)
                .description(description)
                .createTime(LocalDateTime.now())
                .build();
        save(creditLog);
        log.info("【记录积分流水】userId={}, change={}, balance={}, desc={}", userId, changeAmount, balance, description);
    }

    @Override
    public PageResult<CreditLog> queryUserCreditLogs(Long userId, PageQuery query) {
        Page<CreditLog> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                // create_time 只精确到秒，签到与购票抵扣同秒发生很常见，必须带第二排序键
                .orderByDesc("id")
                .page(query.toPage());
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
