package com.fc.v2.service;

import com.fc.v2.model.auto.TIsAccFlow;
import com.fc.v2.model.auto.TIsAccHandle;
import com.fc.v2.model.auto.TIsAccLevel;
import com.fc.v2.model.auto.TIsAccUrge;
import com.fc.v2.service.acc.AccUrgeScreen;
import com.fc.v2.service.acc.HandleCmd;

import java.util.List;
import java.util.Map;

/**
 * 事故报告调查处置逐级流转 Service 接口。
 *
 * 处置单字段固定、状态六档；逐级办理、四动作；作废只两入口；结案写死。
 *
 * @author fuce
 * @date 2026-10-03
 */
public interface ITIsAccFlowService {

    /** 上报：建单即进第一级；关联事故（哪起、什么等级，等级取事故台账）落单后不许改 */
    TIsAccFlow report(Long accidentId, String remark, String reporter);

    /** 上报人补齐材料后重新上报（自动从第一级重头走，轮次加一） */
    TIsAccFlow resubmit(Long id, String supplementNote, String reporter);

    /** 当前层级办理：办结/否决/补充材料/转交他级；cmd.operator 为实际落笔者 */
    TIsAccFlow handle(HandleCmd cmd);

    /** 上报人在单子进任何一级办理前撤回作废 */
    TIsAccFlow withdraw(Long id, String reporter);

    /** 管理员对已结案单归档作废（结论与结案时间原样封死） */
    TIsAccFlow archive(Long id, String operator);

    /** 按主键回查 */
    TIsAccFlow selectById(Long id);

    /** 台账：全部状态都翻得到（含已作废，作废单只是不进待办与统计） */
    List<TIsAccFlow> listLedger(Integer status, String flowNo);

    /** 我的待办：只返回在途且当前待办层级恰好在当前登录人办理角色内的单 */
    List<TIsAccFlow> myTodo(String username);

    /** 我上报的单（含补材料退回、已作废） */
    List<TIsAccFlow> myReported(String reporter);

    /** 单张全貌：单子 + 逐级办理意见 + 催办留痕 */
    Map<String, Object> detail(Long id);

    /** 一张单的全部办理意见（按时刻） */
    List<TIsAccHandle> listHandles(Long flowId);

    /** 一张单的全部催办留痕（按时刻） */
    List<TIsAccUrge> listUrges(Long flowId);

    /** 待办/统计点算：已作废不参与；返回各状态单数与当前用户待办数 */
    Map<String, Object> stats(String username);

    /** 定时/手工催办扫描，返回一屏结果 */
    AccUrgeScreen urgeScan();

    // ---------- 层级配置（平台写死） ----------

    /** 启用链条（status=0 按 level_no 升序），流转永远现取 */
    List<TIsAccLevel> listActiveLevels();

    /** 配置台账：启用停用都翻得到 */
    List<TIsAccLevel> listAllLevels();

    /** 登记一级 */
    TIsAccLevel addLevel(TIsAccLevel level);

    /** 停用一级：有在途单还挂在这级时拒停 */
    TIsAccLevel retireLevel(Long id);
}
