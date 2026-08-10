package com.sf.station.common;

/**
 * 错误码表（前后端契约，见 docs/03-API.md §错误码）。
 *
 * <p>P2002 与 P2003 必须区分：站员看到"该码 3 天前已出库、冷却至 X 日"才能理解
 * 为什么架上空着却不让用，只回"码已占用"会直接引发投诉。
 */
public enum ErrorCode {

    /** 成功 */
    OK("0", 200, "success"),

    /** 参数校验失败，data 为字段错误映射 */
    PARAM_INVALID("P1001", 400, "参数校验失败"),
    /** 取件码格式非法，data 为 {input, expectedPattern} */
    CODE_FORMAT_INVALID("P1002", 400, "取件码格式非法"),

    /** 运单号未完结重复入库，data 为 {existingParcelId, inboundAt} */
    TRACKING_DUPLICATED("P2001", 409, "运单号未完结重复入库"),
    /** 取件码被在库包裹占用，data 为 {trackingNo, inboundAt, suggestedCode} */
    CODE_OCCUPIED("P2002", 409, "取件码被在库包裹占用"),
    /** 取件码处于冷却期，data 为 {outboundAt, reusableAt, suggestedCode} */
    CODE_COOLING("P2003", 409, "取件码处于冷却期"),
    /** 该排码空间耗尽，data 为 {prefix, alternatives[]} */
    CODE_SPACE_EXHAUSTED("P2004", 409, "该排码空间耗尽"),
    /** 包裹已取件，data 为 {outboundAt, operator} */
    ALREADY_PICKED_UP("P2005", 409, "包裹已取件"),
    /** 撤销失败，码已被复用，data 为 {currentHolderTrackingNo} */
    CODE_ALREADY_REUSED("P2006", 409, "撤销失败，取件码已被复用"),
    /** 非法状态流转，data 为 {currentStatus, expected} */
    ILLEGAL_STATUS("P2007", 409, "非法状态流转"),
    /**
     * 撤销失败，该运单号已有新的未完结记录，data 为 {activeParcelId, inboundAt}。
     *
     * <p>补充于实施阶段（文档 §2 F10 未覆盖）：TC-09 允许取件后同运单号再次入库，
     * 此时对历史行执行撤销会把 activeFlag 恢复为 1 从而撞 uk_tracking_active。
     * 不复用 P2001，因为语义与处置动作不同——这里要提示站员先处理新入库的那件。
     */
    TRACKING_ACTIVE_EXISTS("P2008", 409, "撤销失败，该运单号已有新的未完结记录"),

    /** 货位繁忙（并发重试耗尽），提示重试 */
    CODE_SPACE_BUSY("P2009", 409, "货位繁忙，请重试"),

    /** 手动冷却值超安全上限，data 为 {requested, maxAllowed} */
    COOLDOWN_UNSAFE("P3001", 400, "手动冷却值超出安全上限"),

    /** 资源不存在 */
    NOT_FOUND("P4004", 404, "资源不存在"),

    /** 系统内部错误，堆栈只进日志，不返回前端 */
    INTERNAL("P5000", 500, "系统繁忙，请稍后重试");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
