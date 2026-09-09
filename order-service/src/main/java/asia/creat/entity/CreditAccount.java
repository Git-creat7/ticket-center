package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@TableName("tb_credit_account")
public class CreditAccount {

    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    private Integer credits;
}
