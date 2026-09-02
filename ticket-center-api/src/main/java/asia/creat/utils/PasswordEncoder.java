package asia.creat.utils;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class PasswordEncoder {

    private static final BCryptPasswordEncoder DELEGATE = new BCryptPasswordEncoder();

    private PasswordEncoder() {
    }

    public static String encode(String password) {
        return DELEGATE.encode(password);
    }

    public static Boolean matches(String encodedPassword, String rawPassword) {
        if (encodedPassword == null || encodedPassword.isBlank() || rawPassword == null) {
            return false;
        }

        try {
            return DELEGATE.matches(rawPassword, encodedPassword);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
