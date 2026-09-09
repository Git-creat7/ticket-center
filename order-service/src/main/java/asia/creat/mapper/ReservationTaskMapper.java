package asia.creat.mapper;

import asia.creat.entity.ReservationTask;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ReservationTaskMapper extends BaseMapper<ReservationTask> {
    void add(@Param("reservationId") Long reservationId, @Param("type") int type);

    default void complete(Long reservationId, int type) {
        update(null, new LambdaUpdateWrapper<ReservationTask>()
                .eq(ReservationTask::getReservationId, reservationId)
                .eq(ReservationTask::getType, type)
                .eq(ReservationTask::getStatus, 0)
                .set(ReservationTask::getStatus, 1)
                .set(ReservationTask::getLastError, null));
    }

    List<ReservationTask> findDueTasks();

    int claim(Long id);

    default void recordError(Long id, String error) {
        update(null, new LambdaUpdateWrapper<ReservationTask>()
                .eq(ReservationTask::getId, id)
                .eq(ReservationTask::getStatus, 0)
                .set(ReservationTask::getLastError, error));
    }
}
