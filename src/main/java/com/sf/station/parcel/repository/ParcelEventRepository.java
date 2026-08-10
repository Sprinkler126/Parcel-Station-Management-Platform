package com.sf.station.parcel.repository;

import com.sf.station.parcel.domain.ParcelEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParcelEventRepository extends JpaRepository<ParcelEvent, Long> {

    List<ParcelEvent> findByParcelIdOrderByOccurredAtAscIdAsc(Long parcelId);
}
