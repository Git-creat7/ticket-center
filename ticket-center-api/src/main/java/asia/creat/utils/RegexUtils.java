package asia.creat.utils;

import cn.hutool.core.util.StrUtil;

public class RegexUtils {

    public static boolean isPhoneInvalid(String phone){
        return mismatch(phone);
    }


    private static boolean mismatch(String str){
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(RegexPatterns.PHONE_REGEX);
    }
}
