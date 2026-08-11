package com.sf.station.code.repository;

import com.sf.station.code.domain.CodeSpace;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodeSpaceRepository extends JpaRepository<CodeSpace, String> {

    @Query("select s from CodeSpace s where s.prefix = :prefix and s.enabled = 1")
    Optional<CodeSpace> findEnabled(@Param("prefix") String prefix);

    @Query("select s from CodeSpace s where s.enabled = 1 order by s.prefix")
    List<CodeSpace> findAllEnabled();

    List<CodeSpace> findAllByOrderByPrefixAsc();

    /** SHELF 范围：某货架下的所有启用排，如 shelf=15 匹配 15-1、15-2 */
    @Query("select s from CodeSpace s where s.enabled = 1 and s.prefix like concat(:shelf, '-%') order by s.prefix")
    List<CodeSpace> findEnabledByShelf(@Param("shelf") String shelf);
}
