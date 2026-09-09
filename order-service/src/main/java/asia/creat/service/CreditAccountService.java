package asia.creat.service;

import asia.creat.entity.CreditAccount;
import com.baomidou.mybatisplus.extension.service.IService;

public interface CreditAccountService extends IService<CreditAccount> {

    int getBalance(Long userId);

    CreditAccount lockAccount(Long userId);
}
