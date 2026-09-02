package asia.creat.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UserProfileUpdateDTO {

    @Size(min = 1, max = 20, message = "昵称长度必须在 1 到 20 个字符之间")
    private String nickName;

    private String icon;

    @Size(max = 50, message = "城市名称不能超过 50 个字符")
    private String city;

    @Size(max = 255, message = "个人介绍不能超过 255 个字符")
    private String introduce;

    private Boolean gender;

    private LocalDate birthday;
}
