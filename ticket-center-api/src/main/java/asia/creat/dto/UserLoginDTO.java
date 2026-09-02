package asia.creat.dto;

import asia.creat.utils.RegexPatterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginDTO {

    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = RegexPatterns.PHONE_REGEX, message = "手机号格式错误")
    private String phone;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = RegexPatterns.VERIFY_CODE_REGEX, message = "验证码格式错误(6位数字)")
    private String code;

    private String password;
}
