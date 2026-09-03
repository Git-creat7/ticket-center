package asia.creat.auth;

import asia.creat.support.IntegrationTestcontainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 错误响应协议的边界。
@SpringBootTest
@AutoConfigureMockMvc
public class ErrorProtocolTest extends IntegrationTestcontainers {

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
        // 业务异常保留 HTTP 200，具体错误码放在响应体中。
        mockMvc.perform(get("/event/999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
