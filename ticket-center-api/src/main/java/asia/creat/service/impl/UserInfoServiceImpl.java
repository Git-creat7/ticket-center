package asia.creat.service.impl;

import asia.creat.entity.UserInfo;
import asia.creat.mapper.UserInfoMapper;
import asia.creat.service.UserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements UserInfoService {

}
