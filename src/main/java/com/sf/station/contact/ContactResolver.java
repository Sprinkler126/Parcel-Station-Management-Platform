package com.sf.station.contact;

import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 联系方式解析（文档 §8.4 / F14）。
 *
 * <p>隐私面单让手机号不再可靠：面单上印的可能是掩码号 {@code 138****5678}，
 * 也可能是 AXB 中间号 {@code 17012345678,8462}。中间号一单一号、签收后回收复用，
 * <b>无法作为客户身份标识</b>，而客户上门报的仍是真实后四位。
 *
 * <p>因此手机号的两个职责必须拆分：
 * {@code contactNo} 负责"联系"，{@code realSuffix} 负责"身份识别与检索"。
 *
 * <p>校验按类型分支——真实号严格校验，虚拟号只校验字符集与长度上限，
 * 避免把合法的带分机号误判为非法输入。
 */
@Component
public class ContactResolver {

    /** 真实号：11 位大陆手机号 */
    private static final Pattern REAL = Pattern.compile("^1[3-9]\\d{9}$");
    /** 掩码号：138****5678，中间为任意非数字掩码字符 */
    private static final Pattern MASKED = Pattern.compile("^1[3-9]\\d\\D{0,6}\\d{4}$");
    /** AXB 虚拟号：17012345678,8462（分机号可选） */
    private static final Pattern VIRTUAL = Pattern.compile("^\\d{7,13}([,，]\\d{1,6})?$");
    /** 真实后四位 */
    private static final Pattern SUFFIX = Pattern.compile("^\\d{4}$");

    /**
     * @param rawContact   面单原始号码
     * @param manualSuffix 站员补录的真实后四位，仅对虚拟号有意义
     */
    public ContactInfo resolve(String rawContact, String manualSuffix) {
        if (rawContact == null || rawContact.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "联系号不能为空");
        }
        String suffix = normalizeSuffix(manualSuffix);

        // 两级归一化：
        // - loose 只去空白，保留 * - 等掩码字符，供掩码号判定（138----5678 若去掉 - 会被误读成 1385678）
        // - compact 进一步去掉连字符，供真实号与虚拟号判定（138-1234-5678 是合法真实号写法）
        String loose = PhoneNormalizer.stripBlank(rawContact);
        String compact = PhoneNormalizer.normalize(rawContact);

        // 真实号：直接截取后四位，来源 DERIVED
        if (REAL.matcher(compact).matches()) {
            return new ContactInfo(compact, ContactType.REAL,
                    compact.substring(compact.length() - 4), SuffixSource.DERIVED);
        }

        // 掩码号：从尾段提取后四位，来源 MASK。须先于虚拟号判断，掩码号含非数字字符
        if (MASKED.matcher(loose).matches()) {
            return new ContactInfo(loose, ContactType.MASKED,
                    loose.substring(loose.length() - 4), SuffixSource.MASK);
        }

        // AXB 虚拟号：realSuffix 允许为空，支持后续补录
        if (VIRTUAL.matcher(compact).matches()) {
            return new ContactInfo(compact, ContactType.VIRTUAL,
                    suffix, suffix == null ? null : SuffixSource.MANUAL);
        }

        throw new BizException(ErrorCode.PARAM_INVALID, "联系号格式无法识别：" + rawContact);
    }

    /** 校验补录尾号 */
    public String normalizeSuffix(String manualSuffix) {
        if (manualSuffix == null || manualSuffix.isBlank()) {
            return null;
        }
        String s = PhoneNormalizer.normalize(manualSuffix);
        if (!SUFFIX.matcher(s).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "真实后四位应为 4 位数字：" + manualSuffix);
        }
        return s;
    }
}
