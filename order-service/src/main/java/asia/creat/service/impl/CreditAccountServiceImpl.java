package asia.creat.service.impl;

import asia.creat.entity.CreditAccount;
import asia.creat.mapper.CreditAccountMapper;
import asia.creat.service.CreditAccountService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreditAccountServiceImpl extends ServiceImpl<CreditAccountMapper, CreditAccount>
        implements CreditAccountService {

    @Override
    public int getBalance(Long userId) {
        CreditAccount account = getById(userId);
        return account == null ? 0 : account.getCredits();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditAccount lockAccount(Long userId) {
        baseMapper.add(userId);
        return baseMapper.selectForUpdate(userId);
    }
}
