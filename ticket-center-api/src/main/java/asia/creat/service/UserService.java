package asia.creat.service;

import asia.creat.dto.PasswordLoginDTO;
import asia.creat.dto.SetPasswordDTO;
import asia.creat.dto.UserLoginDTO;
import asia.creat.dto.UserProfileUpdateDTO;
import asia.creat.entity.User;
import asia.creat.vo.UserVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface UserService extends IService<User> {

    void sendCode(String phone);

    String login(UserLoginDTO loginDTO);

    String loginByPassword(PasswordLoginDTO loginDTO);

    void setPassword(SetPasswordDTO passwordDTO);

    boolean hasPassword();

    void logout(String token);

    UserVO queryUserById(Long userId);

    UserVO getCurrentUser();

    List<UserVO> queryUsersByIdsSorted(List<Long> ids);

    void updateProfile(UserProfileUpdateDTO updateDTO, String token);
}
