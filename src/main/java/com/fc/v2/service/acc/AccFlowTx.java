package com.fc.v2.service.acc;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TIsAccFlowMapper;
import com.fc.v2.mapper.auto.TIsAccLevelMapper;
import com.fc.v2.mapper.auto.TIsAccSignMapper;
import com.fc.v2.mapper.auto.TIsAccUrgeMapper;
import com.fc.v2.model.auto.TIsAccFlow;
import com.fc.v2.model.auto.TIsAccLevel;
import com.fc.v2.model.auto.TIsAccSign;
import com.fc.v2.model.auto.TIsAccUrge;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 事故调查处置流转的事务执行器（单开 Bean，让 {@link Transactional} 经代理生效）。
 *
 * 每一个动作在同一事务内：先原子翻转主单（WHERE 钉死状态/层级/轮次，0 行即已被别人先推动），
 * 再落一条意见/事件留痕，两步缺一不可。结案列（状态=已结案、结论、结案时间）一旦写死，
 * 本类再无任何 SQL 会去碰它们——要查变化只查 t_is_acc_sign。
 *
 * @author fuce
 * @date 2026-10-03
 */
@Component
public class AccFlowTx {

    @Resource
    private TIsAccFlowMapper flowMapper;
    @Resource
    private TIsAccSignMapper signMapper;
    @Resource
    private TIsAccUrgeMapper urgeMapper;
    @Resource
    private TIsAccLevelMapper levelMapper;

    /** 上报：建单直接挂链条第一级、落"已上报"。关联事故名/等级落单，之后谁都不许改这两栏。 */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow report(String accTitle, Integer accLevel, String remark, String reporter,
                             AccChain chain, Date at) {
        if (chain.isEmpty()) {
            throw new IllegalStateException("平台还没配置任何启用的办理层级，事故单无处可送");
        }
        if (accTitle == null || accTitle.trim().isEmpty()) {
            throw new IllegalArgumentException("必须写明关联事故（哪一起）");
        }
        if (accLevel == null || accLevel < 1 || accLevel > 4) {
            throw new IllegalArgumentException("事故等级只许 1特别重大 2重大 3较大 4一般");
        }
        TIsAccFlow a = new TIsAccFlow();
        a.setAccNo(nextAccNo(at));
        a.setAccTitle(accTitle.trim());
        a.setAccLevel(accLevel);
        a.setReporter(reporter);
        a.setReportTime(at);
        a.setCurLevelNo(chain.first().getLevelNo());
        a.setStatus(AccConst.ST_REPORTED);
        a.setRoundNo(1);
        a.setLevelEnteredAt(at);
        a.setRemark(remark);
        a.setCreateTime(at);
        a.setUpdateTime(at);
        flowMapper.insert(a);
        // 上报事件不挂层级意见，只记"谁报的、何时报的"；第一级办理意见由真正办理那一步落。
        signMapper.insert(event(a, null, AccConst.EVT_REPORT, reporter, reporter, 0, null,
                null, null, "事故上报，进第一级：" + chain.first().getLevelName(), at));
        return a;
    }

    /**
     * 补齐后重新上报。
     * 只许从"已上报、当前待办为空（被退回补材料）"的态重报；上一轮必须有 SUPPLEMENT 记录。
     * 重报挂第一级、轮次加一，旧轮次意见全部留着不抹。
     */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow resubmit(Long id, String supplementNote, String operator, AccChain chain, Date at) {
        if (chain.isEmpty()) {
            throw new IllegalStateException("平台还没配置任何启用的办理层级，事故单无处可送");
        }
        TIsAccFlow a = mustGet(id);
        if (a.getStatus() == null || a.getStatus() != AccConst.ST_REPORTED || a.getCurLevelNo() != null) {
            throw new IllegalStateException("只有被退回补充材料的事故单能重新上报，当前："
                    + AccConst.statusText(a.getStatus()));
        }
        if (!operator.equals(a.getReporter())) {
            throw new IllegalStateException("只有上报人本人能补齐重新上报这张单");
        }
        int round = a.getRoundNo() == null ? 1 : a.getRoundNo();
        int sup = signMapper.selectCount(new QueryWrapper<TIsAccSign>()
                .eq("acc_id", id)
                .eq("round_no", round)
                .eq("action", AccConst.ACT_SUPPLEMENT));
        if (sup == 0) {
            throw new IllegalStateException("这张单不是补充材料退回状态，不允许重新上报");
        }
        if (supplementNote != null && !supplementNote.trim().isEmpty()) {
            // 补齐说明在重报前写进备注；事故名/等级两栏始终不许动。
            TIsAccFlow upd = new TIsAccFlow();
            upd.setId(id);
            upd.setRemark(supplementNote);
            flowMapper.updateById(upd);
        }
        int rows = flowMapper.resubmit(id, chain.first().getLevelNo(), round, at);
        if (rows == 0) {
            throw new IllegalStateException("重新上报未生效：单子已被先动过，请刷新重试");
        }
        TIsAccFlow saved = mustGet(id);
        signMapper.insert(event(saved, null, AccConst.EVT_RESUBMIT, operator, operator, 0, null,
                null, null, "补齐材料后重新上报，从第一级重头走起", at));
        return saved;
    }

    /**
     * 办理：四动作。调用方（Service）已验过当前登录人持有本层办理角色，这里只管业务落库。
     * 每一级办的是自己这层的意见：四种动作都必须带非空意见；转交还须把本级意见记全。
     */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow handle(HandleCmd cmd, AccChain chain, Date at) {
        TIsAccFlow a = mustGet(cmd.getId());
        if (!AccConst.isInflight(a.getStatus()) || a.getCurLevelNo() == null) {
            throw new IllegalStateException("只有在途且停在某一待办层级的单子能办理，当前状态："
                    + AccConst.statusText(a.getStatus()));
        }
        int curNo = a.getCurLevelNo();
        TIsAccLevel cur = chain.byNo(curNo);
        if (cur == null) {
            throw new IllegalStateException("当前待办层级已停用或不存在，办理中止，等平台配置");
        }
        int round = a.getRoundNo() == null ? 1 : a.getRoundNo();
        String comment = cmd.getComment() == null ? "" : cmd.getComment().trim();
        if (comment.isEmpty()) {
            throw new IllegalStateException("办理必须写下本级意见，不许空着落笔");
        }

        // 代办三栏：代办人、原办理人、代办原因；代办不改层级归属。
        boolean agent = isAgent(cmd.getOperator(), cmd.getIntendedHandler());
        String intended = agent ? cmd.getIntendedHandler().trim() : cmd.getOperator();
        String agentReason = null;
        if (agent) {
            if (cmd.getAgentReason() == null || cmd.getAgentReason().trim().isEmpty()) {
                throw new IllegalStateException("代办必须写明代办原因");
            }
            agentReason = cmd.getAgentReason().trim();
        }

        String action = cmd.getAction();
        int rows;
        switch (action == null ? "" : action) {
            case AccConst.ACT_COMPLETE: {
                TIsAccLevel next = chain.nextOf(curNo);
                if (next == null) {
                    rows = flowMapper.completeClose(a.getId(), curNo, round, at);
                } else {
                    // 状态前进到"下一级所代表的阶段"（与链条位次对齐，在处置中封顶）。
                    int newStatus = Math.min(chain.indexOfNo(next.getLevelNo()), AccConst.MAX_INFLIGHT_STATUS);
                    rows = flowMapper.completeAdvance(a.getId(), a.getStatus(), curNo, round,
                            next.getLevelNo(), newStatus, at);
                }
                break;
            }
            case AccConst.ACT_VETO:
                // 否决直接结案封卷（结论=否决），不许再留在流程里。
                rows = flowMapper.vetoClose(a.getId(), curNo, round, at);
                break;
            case AccConst.ACT_SUPPLEMENT:
                // 退回上报人；轮次不在这里加，重报时才加一，重走第一级。
                rows = flowMapper.backToReporter(a.getId(), curNo, round, at);
                break;
            case AccConst.ACT_TRANSFER: {
                rows = doTransfer(a, cur, round, cmd, comment, chain, at);
                break;
            }
            default:
                throw new IllegalArgumentException("办理动作只许 办结/否决/补充材料/转交他级：" + action);
        }
        if (rows == 0) {
            throw new IllegalStateException("办理未生效：单子已被别人先推动，请刷新重试");
        }

        TIsAccLevel target = AccConst.ACT_TRANSFER.equals(action)
                ? chain.byNo(cmd.getTargetLevelNo()) : null;
        signMapper.insert(event(mustGet(a.getId()), cur, action, cmd.getOperator(), intended,
                agent ? 1 : 0, agentReason,
                target == null ? null : target.getLevelNo(),
                target == null ? null : target.getLevelName(),
                comment, at));
        return mustGet(a.getId());
    }

    /** 转交校验：目标级必须是链条上启用的、且不是本级；本级意见不许只留一句"转走了"。 */
    private int doTransfer(TIsAccFlow a, TIsAccLevel cur, int round, HandleCmd cmd,
                           String comment, AccChain chain, Date at) {
        if (cmd.getTargetLevelNo() == null) {
            throw new IllegalStateException("转交他级必须指明转到哪一级");
        }
        TIsAccLevel target = chain.byNo(cmd.getTargetLevelNo());
        if (target == null) {
            throw new IllegalStateException("转交目标层级不存在或已停用，单子不许挂空");
        }
        if (cmd.getTargetLevelNo().equals(cur.getLevelNo())) {
            throw new IllegalStateException("不许转交给本级自己");
        }
        if (comment.length() < 4 || "转走了".equals(comment)) {
            throw new IllegalStateException("转交他级必须把本级的处理意见记全，不能只留一句\"转走了\"");
        }
        return flowMapper.transferTo(a.getId(), cur.getLevelNo(), round, target.getLevelNo(), at);
    }

    /**
     * 上报人撤回作废：只许在单子还没被任何一级真正办理前——没有任何层级办理动作记录。
     * 上报后停在第一级、尚未办理可撤；补材料退回后的单已被办过，只许补齐重报或等否决，不撤。
     */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow withdraw(Long id, String operator, Date at) {
        TIsAccFlow a = mustGet(id);
        if (!operator.equals(a.getReporter())) {
            throw new IllegalStateException("只有上报人本人能撤自己的单");
        }
        if (a.getStatus() == null || a.getStatus() != AccConst.ST_REPORTED) {
            throw new IllegalStateException("只有已上报的单子能撤回，当前状态：" + AccConst.statusText(a.getStatus()));
        }
        long handled = signMapper.selectCount(new QueryWrapper<TIsAccSign>()
                .eq("acc_id", id)
                .in("action", AccConst.ACT_COMPLETE, AccConst.ACT_VETO,
                        AccConst.ACT_SUPPLEMENT, AccConst.ACT_TRANSFER));
        if (handled > 0) {
            throw new IllegalStateException("单子已有层级办理记录，中途不许撤回作废；有异议请走否决或补材料");
        }
        int rows = flowMapper.withdraw(id, at);
        if (rows == 0) {
            throw new IllegalStateException("撤回未生效：单子已被先动过");
        }
        signMapper.insert(event(mustGet(id), null, AccConst.EVT_WITHDRAW, operator, operator,
                0, null, null, null, "上报人在任何一级办理前撤回作废", at));
        return mustGet(id);
    }

    /** 管理员归档：只许对已结案的单；结论、事故等级、结案时间不在更新列里，原样封死。 */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow archive(Long id, String operator, Date at) {
        TIsAccFlow a = mustGet(id);
        if (a.getStatus() == null || a.getStatus() != AccConst.ST_CLOSED) {
            throw new IllegalStateException("只有已结案的单子能归档作废，当前状态：" + AccConst.statusText(a.getStatus()));
        }
        int rows = flowMapper.archive(id, at);
        if (rows == 0) {
            throw new IllegalStateException("归档未生效：单子状态已变");
        }
        signMapper.insert(event(mustGet(id), null, AccConst.EVT_ARCHIVE, operator, operator,
                0, null, null, null, "结案后管理员归档；结论、事故等级与结案时间原样封死", at));
        return mustGet(id);
    }

    /**
     * 层级超时催办扫描：只扫在途且当前有待办层级的单；卡在某一层超过该层 urge_hours 就催一次并留痕，
     * 同一轮同一级只催一次（先查）。已作废/已结案/退回补材料的单一律扫不到。
     */
    @Transactional(rollbackFor = Exception.class)
    public UrgeScreen urgeScan(AccChain chain, Date at) {
        List<TIsAccFlow> running = flowMapper.selectList(new QueryWrapper<TIsAccFlow>()
                .eq("del_flag", 0)
                .in("status", AccConst.ST_REPORTED, AccConst.ST_INVEST,
                        AccConst.ST_DETERMINE, AccConst.ST_DISPOSE)
                .isNotNull("cur_level_no"));
        UrgeScreen screen = new UrgeScreen();
        screen.setRunningCnt(running.size());
        int urged = 0;
        int within = 0;
        for (TIsAccFlow a : running) {
            int levelNo = a.getCurLevelNo();
            int round = a.getRoundNo() == null ? 1 : a.getRoundNo();
            Integer limitHours = chain.urgeHoursOf(levelNo);
            long used = a.getLevelEnteredAt() == null ? 0L
                    : (at.getTime() - a.getLevelEnteredAt().getTime()) / (60L * 60 * 1000);
            if (used < limitHours) {
                within++;
                continue;
            }
            // 去重：本轮本级已催过、且"本次进层时刻"不晚于上次催办，说明催过之后一直没动——不重复催。
            // 转交出去又转回来会刷新进层时刻，再次卡住超时仍要再催。
            List<TIsAccUrge> prior = urgeMapper.selectList(new QueryWrapper<TIsAccUrge>()
                    .eq("acc_id", a.getId())
                    .eq("round_no", round)
                    .eq("level_no", levelNo)
                    .orderByDesc("urged_at", "id")
                    .last("limit 1"));
            if (!prior.isEmpty()
                    && a.getLevelEnteredAt() != null
                    && !a.getLevelEnteredAt().after(prior.get(0).getUrgedAt())) {
                within++;
                continue;
            }
            TIsAccLevel lvl = chain.byNo(levelNo);
            TIsAccUrge u = new TIsAccUrge();
            u.setAccId(a.getId());
            u.setAccNo(a.getAccNo());
            u.setRoundNo(round);
            u.setLevelNo(levelNo);
            u.setLevelName(lvl == null ? ("第" + levelNo + "级") : lvl.getLevelName());
            u.setOverdueHours((int) used);
            u.setUrgedAt(at);
            u.setDetail("事故单[" + a.getAccNo() + "]卡在" + u.getLevelName()
                    + "已 " + used + " 小时（时限 " + limitHours + " 小时），请尽快办理");
            u.setCreateBy("Task");
            u.setCreateTime(at);
            urgeMapper.insert(u);
            urged++;
        }
        screen.setUrgedCnt(urged);
        screen.setWithinCnt(within);
        screen.setMessage("催办扫描：在途 " + running.size() + " 张，新催办 " + urged
                + " 张，时限内（含已催过）" + within + " 张");
        return screen;
    }

    /** 层级配置登记（平台写死，不许重号）。 */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccLevel addLevel(TIsAccLevel level, Date at) {
        if (level.getLevelNo() == null || level.getLevelNo() < 1) {
            throw new IllegalArgumentException("层级次序必须是不小于 1 的整数");
        }
        if (level.getLevelName() == null || level.getLevelName().trim().isEmpty()) {
            throw new IllegalArgumentException("层级名不能为空");
        }
        if (level.getHandlerRoleId() == null) {
            throw new IllegalArgumentException("本层办理角色不能为空：角色对不上的层级没法办理");
        }
        long dup = levelMapper.selectCount(new QueryWrapper<TIsAccLevel>()
                .eq("del_flag", 0)
                .eq("level_no", level.getLevelNo()));
        if (dup > 0) {
            throw new IllegalStateException("层级次序 " + level.getLevelNo() + " 已存在，次序由平台写死、不许重号");
        }
        if (level.getStatus() == null) {
            level.setStatus(0);
        }
        if (level.getDelFlag() == null) {
            level.setDelFlag(0);
        }
        if (level.getUrgeHours() == null) {
            level.setUrgeHours(AccConst.DEFAULT_URGE_HOURS);
        }
        level.setCreateTime(at);
        level.setUpdateTime(at);
        levelMapper.insert(level);
        return level;
    }

    /** 停用层级：有在途单还挂在这级时拒停。 */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccLevel retireLevel(Long id, Date at) {
        TIsAccLevel level = levelMapper.selectById(id);
        if (level == null || (level.getDelFlag() != null && level.getDelFlag() == 1)) {
            throw new IllegalArgumentException("层级不存在：" + id);
        }
        long onTheWay = flowMapper.selectCount(new QueryWrapper<TIsAccFlow>()
                .eq("del_flag", 0)
                .in("status", AccConst.ST_REPORTED, AccConst.ST_INVEST,
                        AccConst.ST_DETERMINE, AccConst.ST_DISPOSE)
                .eq("cur_level_no", level.getLevelNo()));
        if (onTheWay > 0) {
            throw new IllegalStateException("还有 " + onTheWay + " 张在途单挂在[" + level.getLevelName()
                    + "]，处理完才能停用这一级");
        }
        level.setStatus(1);
        level.setUpdateTime(at);
        levelMapper.updateById(level);
        return level;
    }

    private TIsAccFlow mustGet(Long id) {
        TIsAccFlow a = flowMapper.selectById(id);
        if (a == null || (a.getDelFlag() != null && a.getDelFlag() == 1)) {
            throw new IllegalArgumentException("事故单不存在：" + id);
        }
        return a;
    }

    private boolean isAgent(String operator, String intended) {
        return intended != null && !intended.trim().isEmpty() && !intended.trim().equals(operator);
    }

    private TIsAccSign event(TIsAccFlow a, TIsAccLevel level, String action,
                             String handler, String intended, int agentFlag, String agentReason,
                             Integer targetLevelNo, String targetLevelName,
                             String comment, Date at) {
        TIsAccSign s = new TIsAccSign();
        s.setAccId(a.getId());
        s.setAccNo(a.getAccNo());
        s.setRoundNo(a.getRoundNo());
        s.setLevelNo(level == null ? null : level.getLevelNo());
        s.setLevelName(level == null ? null : level.getLevelName());
        s.setAction(action);
        s.setHandler(handler);
        s.setIntendedHandler(intended);
        s.setAgentFlag(agentFlag);
        s.setAgentReason(agentReason);
        s.setTargetLevelNo(targetLevelNo);
        s.setTargetLevelName(targetLevelName);
        s.setComment(comment);
        s.setHandleTime(at);
        s.setCreateBy(handler);
        s.setCreateTime(at);
        return s;
    }

    private String nextAccNo(Date at) {
        long seq = flowMapper.selectCount(new QueryWrapper<TIsAccFlow>()
                .likeRight("acc_no", "SG" + new SimpleDateFormat("yyyyMMdd").format(at)));
        return "SG" + new SimpleDateFormat("yyyyMMdd").format(at)
                + "-" + String.format("%04d", seq + 1);
    }
}
