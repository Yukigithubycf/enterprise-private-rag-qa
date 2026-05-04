package com.yuki.enterprise_private_rag_qa.model.query;

/**
 * 用户问题意图枚举。
 * 用于约束 Query Rewrite 和 HyDE 的方向，以及 MMR/Reflection 的门控判断。
 */
public enum Intent {

    概念解释("概念解释"),
    实现方法("实现方法"),
    原因分析("原因分析"),
    对比分析("对比分析"),
    故障排查("故障排查"),
    查找定位("查找定位"),
    总结归纳("总结归纳"),
    通用问答("通用问答");

    private final String label;

    Intent(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 精确事实/定位型意图集合，用于 MMR Gate 和 Reflection Gate 直接关闭 */
    public static boolean isPrecisionIntent(String intent) {
        return "精确事实查询".equals(intent)
                || "参数查询".equals(intent)
                || "公式查询".equals(intent)
                || "代码定位".equals(intent)
                || "报错行定位".equals(intent)
                || "查找定位".equals(intent)
                || "定义位置查询".equals(intent)
                || "单点引用型问题".equals(intent);
    }

    /** 需要多角度 context 的意图集合，用于 MMR Gate 和 Reflection Gate 直接开启 */
    public static boolean isMultiAngleIntent(String intent) {
        return "实现方法".equals(intent)
                || "方案设计".equals(intent)
                || "总结归纳".equals(intent)
                || "对比分析".equals(intent)
                || "原因分析".equals(intent)
                || "多步骤解释".equals(intent)
                || "综述类问题".equals(intent)
                || "开放式问题".equals(intent);
    }
}
