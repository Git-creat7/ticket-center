package asia.creat.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScrollResultVO<T> {
    private List<T> list = Collections.emptyList();
    private Long minTime;
    private Integer offset;
}
