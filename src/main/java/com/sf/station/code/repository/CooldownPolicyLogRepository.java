package com.sf.station.code.repository;

import com.sf.station.code.domain.CooldownPolicyLog;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CooldownPolicyLogRepository extends JpaRepository<CooldownPolicyLog, Long> {

    List<CooldownPolicyLog> findByPrefixOrderByDecidedAtDescIdDesc(String prefix, Pageable pageable);
}
