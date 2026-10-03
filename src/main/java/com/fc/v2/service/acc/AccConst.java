package com.fc.v2.service.acc;

/**
 * 事故调查处置领域常量。
 *
 * 状态只此六档，代码里不许出现第七个值；办理只此四动作，作废只此两个入口（撤回/归档）。
 *
 * @author fuce
 * @date 2026-10-03
 */
public final class AccConst {

    private AccConst() {
    }

    /** 状态：已上报（上报后挂第一级，或被退回补材料） */
    public static final int ST_REPORTED = 0;
    /** 状态：调查中（受理初核办结后） */
    public static final int ST_INVEST = 1;
    /** 状态：已认定（事故调查办结后） */
    public static final int ST_DETERMINE = 2;
    /** 状态：处置中（责任认定办结后） */
    public static final int ST_DISPOSE = 3;
    /** 状态：已结案（末级办结，或任一级否决） */
    public static final int ST_CLOSED = 4;
    /** 状态：已作废（上报人撤回 / 管理员归档） */
    public static final int ST_VOID = 5;

    /** 在途状态上界：0..3 都是还在流程里、当前有待办层级的状态（退回补材料时当前待办为空） */
    public static final int MAX_INFLIGHT_STATUS = 3;

    /** 结论：办结 */
    public static final int CON_DONE = 1;
    /** 结论：否决 */
    public static final int CON_VETO = 2;

    /** 办理动作：办结（进下一级；末级办结即结案） */
    public static final String ACT_COMPLETE = "COMPLETE";
    /** 办理动作：否决（直接结案为否决，不留流程） */
    public static final String ACT_VETO = "VETO";
    /** 办理动作：补充材料（退回上报人，重走第一级） */
    public static final String ACT_SUPPLEMENT = "SUPPLEMENT";
    /** 办理动作：转交他级 */
    public static final String ACT_TRANSFER = "TRANSFER";

    /** 流程事件：上报 */
    public static final String EVT_REPORT = "REPORT";
    /** 流程事件：补齐重报 */
    public static final String EVT_RESUBMIT = "RESUBMIT";
    /** 流程事件：上报人撤回 */
    public static final String EVT_WITHDRAW = "WITHDRAW";
    /** 流程事件：管理员归档 */
    public static final String EVT_ARCHIVE = "ARCHIVE";

    /** 默认层级卡办时限（小时），层级未配时兜底 */
    public static final int DEFAULT_URGE_HOURS = 48;

    public static boolean isInflight(Integer status) {
        return status != null && status >= ST_REPORTED && status <= MAX_INFLIGHT_STATUS;
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case ST_REPORTED: return "已上报";
            case ST_INVEST: return "调查中";
            case ST_DETERMINE: return "已认定";
            case ST_DISPOSE: return "处置中";
            case ST_CLOSED: return "已结案";
            case ST_VOID: return "已作废";
            default: return "非法状态(" + status + ")";
        }
    }

    public static String conclusionText(Integer conclusion) {
        if (conclusion == null) {
            return "待结案";
        }
        switch (conclusion) {
            case CON_DONE: return "办结";
            case CON_VETO: return "否决";
            default: return "非法结论(" + conclusion + ")";
        }
    }

    public static String actionText(String action) {
        if (action == null) {
            return "未知";
        }
        switch (action) {
            case ACT_COMPLETE: return "办结";
            case ACT_VETO: return "否决";
            case ACT_SUPPLEMENT: return "补充材料";
            case ACT_TRANSFER: return "转交他级";
            case EVT_REPORT: return "上报";
            case EVT_RESUBMIT: return "补齐重报";
            case EVT_WITHDRAW: return "撤回";
            case EVT_ARCHIVE: return "归档";
            default: return action;
        }
    }
}
