package com.sf.station.parcel.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 分页结果。
 *
 * <p>不直接返回 Spring 的 {@code Page}：它的 JSON 结构随版本变化且含大量
 * 前端用不上的字段（pageable、sort、unpaged…），一旦升级会静默破坏前端契约。
 */
@Schema(description = "分页结果")
public record PageVO<T>(
        @Schema(description = "当前页数据") List<T> content,
        @Schema(description = "页码，从 0 开始") int page,
        @Schema(description = "每页条数") int size,
        @Schema(description = "总条数") long total,
        @Schema(description = "总页数") int totalPages) {

    public static <T> PageVO<T> of(Page<T> p) {
        return new PageVO<>(p.getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), p.getTotalPages());
    }
}
