package asia.creat.mapper;

import asia.creat.entity.CreditAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface CreditAccountMapper extends BaseMapper<CreditAccount> {

    void add(@Param("userId") Long userId);

    CreditAccount selectForUpdate(@Param("userId") Long userId);
}
