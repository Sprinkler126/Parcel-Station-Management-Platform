package com.sf.station.parcel.application;

import com.sf.station.common.AppProperties;
import com.sf.station.parcel.api.dto.ParcelVO;
import com.sf.station.parcel.domain.Parcel;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * ParcelVO 装配器。集中一处注入 Clock 与滞留阈值，
 * 保证所有出口的滞留计算口径一致（INV-4）。
 */
@Component
public class ParcelAssembler {

    private final AppProperties props;
    private final Clock clock;

    public ParcelAssembler(AppProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public ParcelVO toVO(Parcel p) {
        return toVO(p, LocalDateTime.now(clock));
    }

    public ParcelVO toVO(Parcel p, LocalDateTime now) {
        return ParcelVO.of(p, now,
                props.getOverdue().getWarnHours(), props.getOverdue().getAlertHours());
    }

    public List<ParcelVO> toVOList(List<Parcel> list) {
        LocalDateTime now = LocalDateTime.now(clock);
        return list.stream().map(p -> toVO(p, now)).toList();
    }
}
