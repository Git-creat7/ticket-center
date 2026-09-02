package asia.creat.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 错误响应协议的边界（第 14、15 条）。
 *
 * 业务错误走「HTTP 200 + body.code」，只有拦截器鉴权与静态资源缺失用真实状态码。
 * 这条边界原先没有任何用例守着，缺失的图片因此被 Exception 兜底吞成 500。
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ErrorProtocolTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("uploads 下的图片不存在应返回真实 404，而不是被兜底吞成 500")
    void testMissingUpload_ShouldReturn404() throws Exception {
        mockMvc.perform(get("/uploads/no-such-image-9f3a1c.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("业务错误仍是 HTTP 200 + body.code，不能改成真实状态码")
    void testBusinessError_StaysHttp200WithBodyCode() throws Exception {
        // 演出不存在：BusinessException(404, ...) -> body.code=404 但 HTTP 仍为 200。
        // 前端 http.ts 的错误分支只在 2xx 上读 body.msg，
        // 改成真实 404 会让后端文案被 axios 的通用报错覆盖。
        mockMvc.perform(get("/event/999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
