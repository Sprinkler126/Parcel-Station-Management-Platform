package com.sf.station.code.repository;

import com.sf.station.code.domain.CooldownSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CooldownSettingsRepository extends JpaRepository<CooldownSettings, String> {
}
