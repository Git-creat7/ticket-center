package asia.creat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_event")
public class Event implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    private Long categoryId;

    private String venue;

    private String address;

    private Double x;

    private Double y;

    private String mainImage;

    private String images;

    private String intro;

    private LocalDateTime startTime;

    private Integer durationMin;

    private Integer hot;

    private Integer comments;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
