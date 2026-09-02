package asia.creat.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignDayVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 星期名称：周一, 周二, ..., 周日 */
    private String dayName;

    /** 日期格式：yyyy-MM-dd */
    private String date;

    /** 当月几号：如 24 */
    private Integer dayOfMonth;

    /** 是否已签到 */
    private Boolean isSigned;

    /** 是否是今日 */
    private Boolean isToday;

    /** 是否是未来日期 */
    private Boolean isFuture;
}
