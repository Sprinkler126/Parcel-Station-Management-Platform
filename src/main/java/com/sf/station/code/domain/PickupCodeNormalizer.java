package com.sf.station.code.domain;

import com.sf.station.common.BizException;
import com.sf.station.common.ErrorCode;
import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 取件码归一化（文档 §8.1），先于一切校验执行。
 *
 * <p>扫描枪与手工录入会产生字面不同但语义等价的输入：全角减号、前后空格、
 * {@code 15-1-0731} 这类前导零。<b>不归一化，唯一索引形同虚设</b>——
 * {@code 15-1-0731} 与 {@code 15-1-731} 会作为两行共存，但物理上是同一个码。
 *
 * <p>纯函数，无任何依赖。
 */
public final class PickupCodeNormalizer {

    /** 归一化后应满足的形态：1~3 位货架号 + 1~2 段 1~4 位数字 */
    private static final Pattern PATTERN = Pattern.compile("^\\d{1,3}(-\\d{1,4}){1,2}$");
    private static final Pattern SEGMENT = Pattern.compile("\\d{1,4}");
    private static final String EXPECTED = "形如 15-1-7231 或 15-7231";

    private PickupCodeNormalizer() {
    }

    public static PickupCodeVO normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.CODE_FORMAT_INVALID, "取件码为空",
                    Map.of("input", String.valueOf(raw), "expectedPattern", EXPECTED));
        }
        // NFKC 把全角数字与全角减号折叠为半角；再统一各类连字符与下划线；最后去空白
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\u3000]", "")
                .replace('—', '-')   // em dash
                .replace('–', '-')   // en dash
                .replace('－', '-')   // fullwidth hyphen-minus（NFKC 通常已处理，双保险）
                .replace('_', '-');

        String[] seg = s.split("-", -1);
        if (seg.length < 2 || seg.length > 3) {
            throw new BizException(ErrorCode.CODE_FORMAT_INVALID, "取件码段数应为 2 或 3",
                    Map.of("input", raw, "expectedPattern", EXPECTED));
        }

        StringBuilder sb = new StringBuilder();
        int[] nums = new int[seg.length];
        for (int i = 0; i < seg.length; i++) {
            if (!SEGMENT.matcher(seg[i]).matches()) {
                throw new BizException(ErrorCode.CODE_FORMAT_INVALID, "取件码含非法字符",
                        Map.of("input", raw, "expectedPattern", EXPECTED));
            }
            nums[i] = Integer.parseInt(seg[i]);   // 去前导零
            if (i > 0) {
                sb.append('-');
            }
            sb.append(nums[i]);
        }

        String code = sb.toString();
        if (!PATTERN.matcher(code).matches()) {
            throw new BizException(ErrorCode.CODE_FORMAT_INVALID, "取件码格式不符",
                    Map.of("input", raw, "expectedPattern", EXPECTED));
        }

        int seq = nums[seg.length - 1];
        if (seq < 1) {
            throw new BizException(ErrorCode.CODE_FORMAT_INVALID, "取件码排内序号必须大于 0",
                    Map.of("input", raw, "expectedPattern", EXPECTED));
        }

        int last = code.lastIndexOf('-');
        return new PickupCodeVO(code, code.substring(0, last), seq);
    }
}
