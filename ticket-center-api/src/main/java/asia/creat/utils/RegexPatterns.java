package asia.creat.utils;

public abstract class RegexPatterns {

    public static final String PHONE_REGEX = "^1([38][0-9]|4[579]|5[0-3,5-9]|6[6]|7[0135678]|9[89])\\d{8}$";

    public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";

}
