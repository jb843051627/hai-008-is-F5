package com.fc.v2.service.acc;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TIsAccFlowMapper;
import com.fc.v2.mapper.auto.TIsAccHandleMapper;
import com.fc.v2.mapper.auto.TIsAccUrgeMapper;
import com.fc.v2.mapper.auto.TIsAccidentMapper;
import com.fc.v2.model.auto.TIsAccident;
import com.fc.v2.model.auto.TIsAccFlow;
import com.fc.v2.model.auto.TIsAccHandle;
import com.fc.v2.model.auto.TIsAccLevel;
import com.fc.v2.model.auto.TIsAccUrge;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 事故调查处置流转的事务执行器（仿 RectApplyTx：单开 Bean，让 {@link Transactional} 经代理生效）。
 *
 * 每一个动作在同一事务内：先原子翻转主单（WHERE 钉死在途状态/层级/轮次，0 行即已被别人先推动），
 * 再落一条意见/事件留痕，两步缺一不可。结案列（status 终态、结论、结案时间）一旦写死，
 * 本类再无任何 SQL 会去碰它们——要查变化只查 t_is_acc_handle。
 *
 * 与整改的差别：没有草稿态——上报即进第一级；单子状态随所挂层级的"路"走（chain.statusOf）。
 *
 * @author fuce
 * @date 2026-10-03
 */
@Component
public class AccFlowTx {

    @Resource
    private TIsAccFlowMapper flowMapper;
    @Resource
    private TIsAccHandleMapper handleMapper;
    @Resource
    private TIsAccUrgeMapper urgeMapper;
    @Resource
    private TIsAccidentMapper accidentMapper;

    /**
     * 上报：建单即进第一级。关联事故号抄存、事故等级直接取事故台账（不由上报人自报），之后谁都不许改这两栏。
     */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow report(Long accidentId, String remark, String reporter, AccChain chain, Date at) {
        if (accidentId == null) {
            throw new IllegalArgumentException("必须关联一起事故");
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException("平台还没配置任何启用的办理层级，单子无处可送");
        }
        TIsAccident acc = accidentMapper.selectById(accidentId);
        if (acc == null || (acc.getDelFlag() != null && acc.getDelFlag() == 1)) {
            throw new IllegalArgumentException("关联事故不存在：" + accidentId);
        }
        if (acc.getAccLevel() == null || acc.getAccLevel() < 1 || acc.getAccLevel() > 4) {
            throw new IllegalStateException("关联事故的等级没配或不合法（1一般 2较大 3重大 4特别重大），先把事故台账补正");
        }
        TIsAccLevel first = chain.first();
        TIsAccFlow f = new TIsAccFlow();
        f.setFlowNo(nextFlowNo(at));
        f.setAccidentId(acc.getId());
        f.setAccidentNo(acc.getAccidentNo());
        f.setAccidentLevel(acc.getAccLevel());
        f.setReporter(reporter);
        f.setStatus(AccConst.ST_REPORTED);
        f.setRoundNo(1);
        f.setRemark(remark);
        f.setCreateTime(at);
        f.setUpdateTime(at);
        flowMapper.insert(f);

        int rows = flowMapper.report(f.getId(), first.getLevelNo(), chain.statusOf(first), 0, at);
        if (rows == 0) {
            throw new IllegalStateException("上报未生效：单子已被先动过，请刷新重试");
        }
        TIsAccFlow saved = mustGet(f.getId());
        // 上报事件不挂层级（层级意见只属于真正办理的那一级）。
        handleMapper.insert(event(saved, null, AccConst.EVT_REPORT, reporter, reporter,
                0, null, null, null, "上报事故[" + acc.getAccidentNo() + "]，进第一级办理", at));
        return saved;
    }

    /**
     * 补齐后重新上报：上一轮必须有 SUPPLEMENT 记录；重报送第一级、轮次加一。
     */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow resubmit(Long id, String supplementNote, String operator, AccChain chain, Date at) {
        if (chain.isEmpty()) {
            throw new IllegalStateException("平台还没配置任何启用的办理层级，单子无处可送");
        }
        TIsAccFlow f = mustGet(id);
        if (f.getStatus() == null || f.getStatus() != AccConst.ST_REPORTED) {
            throw new IllegalStateException("只有退回在上报人手里的单子能重新上报，当前状态："
                    + AccConst.statusText(f.getStatus()));
        }
        if (!operator.equals(f.getReporter())) {
            throw new IllegalStateException("只有上报人本人能重新上报这张单");
        }
        int round = f.getRoundNo() == null ? 1 : f.getRoundNo();
        int sup = handleMapper.selectCount(new QueryWrapper<TIsAccHandle>()
                .eq("flow_id", id)
                .eq("round_no", round)
                .eq("action", AccConst.ACT_SUPPLEMENT));
        if (sup == 0) {
            throw new IllegalStateException("这张单不是补材料退回状态，不允许重新上报");
        }

        if (supplementNote != null && !supplementNote.trim().isEmpty()) {
            // 补齐说明在翻转前（仍在上报人手里时）写进备注；事故三栏（哪起/号/等级）始终不许动。
            TIsAccFlow upd = new TIsAccFlow();
            upd.setId(id);
            upd.setRemark(supplementNote);
            flowMapper.updateById(upd);
        }
        TIsAccLevel first = chain.first();
        int rows = flowMapper.report(id, first.getLevelNo(), chain.statusOf(first), 1, at);
        if (rows == 0) {
            throw new IllegalStateException("重新上报未生效：单子已被先动过，请刷新重试");
        }
        TIsAccFlow saved = mustGet(id);
        handleMapper.insert(event(saved, null, AccConst.EVT_RESUBMIT, operator, operator,
                0, null, null, null, "补齐材料后重新上报，从第一级重头走起", at));
        return saved;
    }

    /**
     * 办理：四动作。调用方（Service）已验过当前登录人持有本层办理角色，这里只管业务落库。
     * 每一级办的是自己这级的意见：四种动作都必须带非空意见；转交还须把本级意见记全。
     */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow handle(HandleCmd cmd, AccChain chain, Date at) {
        TIsAccFlow f = mustGet(cmd.getId());
        if (f.getStatus() == null
                || (f.getStatus() != AccConst.ST_INVESTIGATING
                && f.getStatus() != AccConst.ST_DETERMINED
                && f.getStatus() != AccConst.ST_DISPOSING)) {
            throw new IllegalStateException("只有在途（调查中/已认定/处置中）的单子能办理，当前状态："
                    + AccConst.statusText(f.getStatus()));
        }
        int curNo = f.getCurLevelNo() == null ? -1 : f.getCurLevelNo();
        TIsAccLevel cur = chain.byNo(curNo);
        if (cur == null) {
            throw new IllegalStateException("当前待办层级已停用或不存在，办理中止，等平台配置");
        }
        // 双保险：主单状态必须与所挂层级的路一致，不一致说明数据被人动过，宁可停住也不自造走向。
        if (chain.statusOf(cur) != f.getStatus()) {
            throw new IllegalStateException("单子状态与当前待办层级所在的路对不上，办理中止，请平台核查配置");
        }
        int round = f.getRoundNo() == null ? 1 : f.getRoundNo();
        String comment = cmd.getComment() == null ? "" : cmd.getComment().trim();
        if (comment.isEmpty()) {
            throw new IllegalStateException("办理必须写下本级意见，不许空着落印");
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
            case AccConst.ACT_FINISH: {
                TIsAccLevel next = chain.nextOf(curNo);
                if (next == null) {
                    rows = flowMapper.finishClose(f.getId(), curNo, round, at);
                } else {
                    rows = flowMapper.finishAdvance(f.getId(), curNo, round,
                            next.getLevelNo(), chain.statusOf(next), at);
                }
                break;
            }
            case AccConst.ACT_VETO:
                // 否决直接落已结案（结论=否决）到此终止，不许再留在流程里。
                rows = flowMapper.vetoClose(f.getId(), curNo, round, at);
                break;
            case AccConst.ACT_SUPPLEMENT:
                // 退回上报人；轮次不在这里加，重报时才加一，重走第一级。
                rows = flowMapper.backToReporter(f.getId(), curNo, round, at);
                break;
            case AccConst.ACT_TRANSFER: {
                rows = doTransfer(f, cur, round, cmd, comment, chain, at);
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
        handleMapper.insert(event(mustGet(f.getId()), cur, action, cmd.getOperator(), intended,
                agent ? 1 : 0, agentReason,
                target == null ? null : target.getLevelNo(),
                target == null ? null : target.getLevelName(),
                comment, at));
        return mustGet(f.getId());
    }

    /** 转交校验：目标级必须是链条上启用的、且不是本级；本级意见不许只留一句"转走了"。 */
    private int doTransfer(TIsAccFlow f, TIsAccLevel cur, int round, HandleCmd cmd,
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
        return flowMapper.transferTo(f.getId(), cur.getLevelNo(), round,
                target.getLevelNo(), chain.statusOf(target), at);
    }

    /**
     * 上报人撤回作废：只许在单子还没进任何一级办理前——仍挂在第一级、首轮、且无任何层级办理记录。
     * 单子一旦被任一级办过（办结/否决/补材料/转交都算），中途只许走否决或补材料，不许撤回。
     */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow withdraw(Long id, String operator, AccChain chain, Date at) {
        TIsAccFlow f = mustGet(id);
        if (!operator.equals(f.getReporter())) {
            throw new IllegalStateException("只有上报人本人能撤自己的单");
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException("平台层级配置为空，无法核对单子位置，撤回中止");
        }
        TIsAccLevel first = chain.first();
        if (f.getStatus() == null || f.getStatus() != chain.statusOf(first)
                || f.getCurLevelNo() == null || !f.getCurLevelNo().equals(first.getLevelNo())) {
            throw new IllegalStateException("只有还停在第一级、未被任何一级接过的单子能撤回，当前状态："
                    + AccConst.statusText(f.getStatus()));
        }
        long levelHandled = handleMapper.selectCount(new QueryWrapper<TIsAccHandle>()
                .eq("flow_id", id)
                .isNotNull("level_no"));
        if (levelHandled > 0) {
            throw new IllegalStateException("单子已被层级接过手，中途不许撤回作废；有异议请走否决或补材料");
        }
        int rows = flowMapper.withdraw(id, first.getLevelNo(), chain.statusOf(first), at);
        if (rows == 0) {
            throw new IllegalStateException("撤回未生效：单子已被先动过");
        }
        handleMapper.insert(event(mustGet(id), null, AccConst.EVT_WITHDRAW, operator, operator,
                0, null, null, null, "上报人在任何一级办理前撤回作废", at));
        return mustGet(id);
    }

    /** 管理员归档：只许对已结案的单；结论与结案时间不在更新列里，原样封死。 */
    @Transactional(rollbackFor = Exception.class)
    public TIsAccFlow archive(Long id, String operator, Date at) {
        TIsAccFlow f = mustGet(id);
        if (f.getStatus() == null || f.getStatus() != AccConst.ST_CLOSED) {
            throw new IllegalStateException("只有已结案的单子能归档作废，当前状态："
                    + AccConst.statusText(f.getStatus()));
        }
        int rows = flowMapper.archive(id, at);
        if (rows == 0) {
            throw new IllegalStateException("归档未生效：单子状态已变");
        }
        handleMapper.insert(event(mustGet(id), null, AccConst.EVT_ARCHIVE, operator, operator,
                0, null, null, null, "结案后管理员归档；结论与结案时间原样封死", at));
        return mustGet(id);
    }

    /**
     * 层级超时催办扫描：只扫在途单（调查中/已认定/处置中）；卡在某一层超过该层 urge_hours 就催一次并留痕，
     * 同一轮同一级只催一次。作废/结案/退回上报人的单一律扫不到。
     */
    @Transactional(rollbackFor = Exception.class)
    public AccUrgeScreen urgeScan(AccChain chain, Date at) {
        List<TIsAccFlow> running = flowMapper.selectList(new QueryWrapper<TIsAccFlow>()
                .eq("del_flag", 0)
                .in("status", Arrays.asList(
                        AccConst.ST_INVESTIGATING, AccConst.ST_DETERMINED, AccConst.ST_DISPOSING))
                .isNotNull("cur_level_no"));
        AccUrgeScreen screen = new AccUrgeScreen();
        screen.setRunningCnt(running.size());
        int urged = 0;
        int within = 0;
        for (TIsAccFlow f : running) {
            int levelNo = f.getCurLevelNo();
            int round = f.getRoundNo() == null ? 1 : f.getRoundNo();
            Integer limitHours = chain.urgeHoursOf(levelNo);
            long used = f.getLevelEnteredAt() == null ? 0L
                    : (at.getTime() - f.getLevelEnteredAt().getTime()) / (60L * 60 * 1000);
            if (used < limitHours) {
                within++;
                continue;
            }
            // 去重：本轮本级已有催办，且"本次进层时刻"不晚于上次催办，说明催过之后一直没动——不重复催。
            // 转交出去又转回来会刷新进层时刻（level_entered_at），那次卡住超时仍要再催。
            List<TIsAccUrge> prior = urgeMapper.selectList(new QueryWrapper<TIsAccUrge>()
                    .eq("flow_id", f.getId())
                    .eq("round_no", round)
                    .eq("level_no", levelNo)
                    .orderByDesc("urged_at", "id")
                    .last("limit 1"));
            if (!prior.isEmpty()
                    && f.getLevelEnteredAt() != null
                    && !f.getLevelEnteredAt().after(prior.get(0).getUrgedAt())) {
                within++;
                continue;
            }
            TIsAccLevel lvl = chain.byNo(levelNo);
            TIsAccUrge u = new TIsAccUrge();
            u.setFlowId(f.getId());
            u.setFlowNo(f.getFlowNo());
            u.setRoundNo(round);
            u.setLevelNo(levelNo);
            u.setLevelName(lvl == null ? ("第" + levelNo + "级") : lvl.getLevelName());
            u.setOverdueHours((int) used);
            u.setUrgedAt(at);
            u.setDetail("事故单[" + f.getFlowNo() + "]卡在" + u.getLevelName()
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

    private TIsAccFlow mustGet(Long id) {
        TIsAccFlow f = flowMapper.selectById(id);
        if (f == null || (f.getDelFlag() != null && f.getDelFlag() == 1)) {
            throw new IllegalArgumentException("处置单不存在：" + id);
        }
        return f;
    }

    private boolean isAgent(String operator, String intended) {
        return intended != null && !intended.trim().isEmpty() && !intended.trim().equals(operator);
    }

    private TIsAccHandle event(TIsAccFlow f, TIsAccLevel level, String action,
                               String handler, String intended, int agentFlag, String agentReason,
                               Integer targetLevelNo, String targetLevelName,
                               String comment, Date at) {
        TIsAccHandle h = new TIsAccHandle();
        h.setFlowId(f.getId());
        h.setFlowNo(f.getFlowNo());
        h.setRoundNo(f.getRoundNo());
        h.setLevelNo(level == null ? null : level.getLevelNo());
        h.setLevelName(level == null ? null : level.getLevelName());
        h.setAction(action);
        h.setHandler(handler);
        h.setIntendedHandler(intended);
        h.setAgentFlag(agentFlag);
        h.setAgentReason(agentReason);
        h.setTargetLevelNo(targetLevelNo);
        h.setTargetLevelName(targetLevelName);
        h.setComment(comment);
        h.setHandleTime(at);
        h.setCreateBy(handler);
        h.setCreateTime(at);
        return h;
    }

    private String nextFlowNo(Date at) {
        long seq = flowMapper.selectCount(new QueryWrapper<TIsAccFlow>()
                .likeRight("flow_no", "SG" + new SimpleDateFormat("yyyyMMdd").format(at)));
        return "SG" + new SimpleDateFormat("yyyyMMdd").format(at)
                + "-" + String.format("%04d", seq + 1);
    }
}
