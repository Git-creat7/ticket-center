package asia.creat.service;

import asia.creat.vo.SignStatusVO;

public interface SignService {

    void sign();

    SignStatusVO getSignStatus();
}
