package asia.creat.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 今日是否已签到 */
    private Boolean isTodaySigned;

    /** 连续签到天数 */
    private Integer continuousDays;

    /** 本月累计签到天数 */
    private Integer monthlyTotalDays;

    /** 当前月份 (1-12) */
    private Integer currentMonth;

    /** 本周 7 天签到明细 (周一到周日) */
    private List<SignDayVO> weekDays;
}
