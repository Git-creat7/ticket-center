package asia.creat.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private int code;
    private String msg;
    private Object data;

    public static Result success() {
        Result res = new Result();
        res.code = 200;
        res.msg = "success";
        return res;
    }

    public static Result success(Object data) {
        Result res = new Result();
        res.code = 200;
        res.msg = "success";
        res.data = data;
        return res;
    }

    public static Result error(String msg) {
        Result res = new Result();
        res.code = 500;
        res.msg = msg;
        return res;
    }

    public static Result error(int code, String msg) {
        Result res = new Result();
        res.code = code;
        res.msg = msg;
        return res;
    }
}
