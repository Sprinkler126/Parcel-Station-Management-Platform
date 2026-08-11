package com.sf.station.parcel.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 批量操作结果（文档 §10：批量接口一律部分成功语义，不因单条失败整体回滚）。
 *
 * <p>为什么不整体回滚：客户站在柜台前，三件包裹里有一件已被家人取走，
 * 把另外两件也回滚掉毫无意义——站员还是得再操作一次，而且第二次仍会失败。
 * 正确做法是成功的成功、失败的逐条说明原因。
 */
@Schema(description = "批量操作结果")
public record BatchResult<T>(
        @Schema(description = "请求总数") int total,
        @Schema(description = "成功数") int succeeded,
        @Schema(description = "失败数") int failed,
        @Schema(description = "成功明细") List<T> success,
        @Schema(description = "失败明细，含错误码与原因") List<Failure> failures) {

    public static <T> BatchResult<T> of(List<T> success, List<Failure> failures) {
        return new BatchResult<>(success.size() + failures.size(),
                success.size(), failures.size(), success, failures);
    }

    /**
     * @param key     标识：批量取件为包裹 ID，批量入库为运单号
     * @param code    业务错误码
     * @param message 人类可读原因
     */
    @Schema(description = "失败明细")
    public record Failure(Object key, String code, String message) {
    }
}
