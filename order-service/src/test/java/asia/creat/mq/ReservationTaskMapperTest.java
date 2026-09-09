package asia.creat.mq;

import asia.creat.entity.ReservationTask;
import asia.creat.mapper.ReservationTaskMapper;
import asia.creat.support.IntegrationTestcontainers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ReservationTaskMapperTest extends IntegrationTestcontainers {

    private static final long RESERVATION_ID = 77991L;

    @Autowired
    private ReservationTaskMapper taskMapper;

    @AfterEach
    void cleanUp() {
        taskMapper.delete(new LambdaQueryWrapper<ReservationTask>()
                .eq(ReservationTask::getReservationId, RESERVATION_ID));
    }

    @Test
    void completingTaskClearsErrorWithoutChangingOtherTaskTypes() {
        taskMapper.add(RESERVATION_ID, ReservationTask.SEND_ORDER);
        taskMapper.add(RESERVATION_ID, ReservationTask.RELEASE);
        ReservationTask sending = task(ReservationTask.SEND_ORDER);
        ReservationTask release = task(ReservationTask.RELEASE);
        taskMapper.recordError(sending.getId(), "发送失败");
        taskMapper.recordError(release.getId(), "回补失败");

        taskMapper.complete(RESERVATION_ID, ReservationTask.SEND_ORDER);

        ReservationTask completed = task(ReservationTask.SEND_ORDER);
        assertEquals(1, completed.getStatus());
        assertNull(completed.getLastError());
        assertEquals(0, task(ReservationTask.RELEASE).getStatus());
        assertEquals("回补失败", task(ReservationTask.RELEASE).getLastError());

        taskMapper.recordError(sending.getId(), "迟到的错误");
        taskMapper.complete(RESERVATION_ID, ReservationTask.SEND_ORDER);
        assertNull(task(ReservationTask.SEND_ORDER).getLastError());
        assertEquals(1, task(ReservationTask.SEND_ORDER).getStatus());
    }

    private ReservationTask task(int type) {
        return taskMapper.selectOne(new LambdaQueryWrapper<ReservationTask>()
                .eq(ReservationTask::getReservationId, RESERVATION_ID)
                .eq(ReservationTask::getType, type));
    }
}
