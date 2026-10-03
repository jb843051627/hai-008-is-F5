package com.fc.v2.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TIsAccFlowMapper;
import com.fc.v2.mapper.auto.TIsAccLevelMapper;
import com.fc.v2.mapper.auto.TIsAccSignMapper;
import com.fc.v2.mapper.auto.TIsAccUrgeMapper;
import com.fc.v2.model.auto.TIsAccFlow;
import com.fc.v2.model.auto.TIsAccLevel;
import com.fc.v2.model.auto.TIsAccSign;
import com.fc.v2.model.auto.TIsAccUrge;
import com.fc.v2.model.auto.TSysRole;
import com.fc.v2.model.auto.TSysUser;
import com.fc.v2.service.ITIsAccFlowService;
import com.fc.v2.service.ITSysRoleService;
import com.fc.v2.service.acc.AccChain;
import com.fc.v2.service.acc.AccConst;
import com.fc.v2.service.acc.AccFlowTx;
import com.fc.v2.service.acc.HandleCmd;
import com.fc.v2.service.acc.UrgeScreen;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事故报告调查处置单 Service 实现。
 *
 * 职责切分：本类做链条现取、角色可见性、台账点算；真正落库的每一步流转都在
 * {@link AccFlowTx} 的单一事务里。
 *
 * @author fuce
 * @date 2026-10-03
 */
@Service
public class TIsAccFlowServiceImpl implements ITIsAccFlowService {

    @Resource
    private TIsAccFlowMapper flowMapper;
    @Resource
    private TIsAccSignMapper signMapper;
    @Resource
    private TIsAccUrgeMapper urgeMapper;
    @Resource
    private TIsAccLevelMapper levelMapper;
    @Resource
    private ITSysRoleService roleService;
    @Resource
    private com.fc.v2.mapper.auto.TSysUserMapper userMapper;
    @Resource
    private AccFlowTx tx;

    @Override
    public TIsAccFlow report(String accTitle, Integer accLevel, String remark, String reporter) {
        return tx.report(accTitle, accLevel, remark, reporter, activeChain(), new Date());
    }

    @Override
    public TIsAccFlow resubmit(Long id, String supplementNote, String reporter) {
        return tx.resubmit(id, supplementNote, reporter, activeChain(), new Date());
    }

    @Override
    public TIsAccFlow handle(HandleCmd cmd) {
        TIsAccFlow a = mustGet(cmd.getId());
        // 不是自己这层的单，连办理都不许——与"连看都看不到"同一道墙：角色对不上直接拒。
        AccChain chain = activeChain();
        TIsAccLevel cur = chain.byNo(a.getCurLevelNo());
        if (cur == null) {
            throw new IllegalStateException("当前待办层级未启用，办理中止");
        }
        Set<Integer> visibleNos = visibleLevelNos(cmd.getOperator());
        if (!visibleNos.contains(cur.getLevelNo())) {
            throw new IllegalStateException("这张单当前停在[" + cur.getLevelName()
                    + "]，不在你的办理层级内，无权办理");
        }
        return tx.handle(cmd, chain, new Date());
    }

    @Override
    public TIsAccFlow withdraw(Long id, String reporter) {
        return tx.withdraw(id, reporter, new Date());
    }

    @Override
    public TIsAccFlow archive(Long id, String operator) {
        return tx.archive(id, operator, new Date());
    }

    @Override
    public TIsAccFlow selectById(Long id) {
        return flowMapper.selectById(id);
    }

    @Override
    public List<TIsAccFlow> listLedger(Integer status, String accNo) {
        QueryWrapper<TIsAccFlow> qw = new QueryWrapper<TIsAccFlow>().eq("del_flag", 0);
        if (status != null) {
            qw.eq("status", status);
        }
        if (accNo != null && !accNo.trim().isEmpty()) {
            qw.likeRight("acc_no", accNo.trim());
        }
        qw.orderByDesc("create_time", "id");
        return flowMapper.selectList(qw);
    }

    @Override
    public List<TIsAccFlow> myTodo(String username) {
        // 按用户名找到其角色，再按角色放层级；不是自己这层的单查都查不出来。
        Set<Integer> visibleNos = visibleLevelNos(username);
        if (visibleNos.isEmpty()) {
            return new ArrayList<>();
        }
        return flowMapper.selectList(new QueryWrapper<TIsAccFlow>()
                .eq("del_flag", 0)
                .in("status", AccConst.ST_REPORTED, AccConst.ST_INVEST,
                        AccConst.ST_DETERMINE, AccConst.ST_DISPOSE)
                .in("cur_level_no", visibleNos)
                .orderByAsc("level_entered_at", "id"));
    }

    @Override
    public List<TIsAccFlow> myReported(String reporter) {
        return flowMapper.selectList(new QueryWrapper<TIsAccFlow>()
                .eq("del_flag", 0)
                .eq("reporter", reporter)
                .orderByDesc("create_time", "id"));
    }

    @Override
    public Map<String, Object> detail(Long id) {
        TIsAccFlow flow = mustGet(id);
        Map<String, Object> data = new HashMap<>();
        data.put("flow", flow);
        data.put("signs", listSigns(id));
        data.put("urges", listUrges(id));
        // "现在停在哪一级、谁该办"：把当前待办层级与它的办理角色一并给出。
        data.put("curLevel", activeChain().byNo(flow.getCurLevelNo()));
        return data;
    }

    @Override
    public List<TIsAccSign> listSigns(Long accId) {
        return signMapper.selectList(new QueryWrapper<TIsAccSign>()
                .eq("acc_id", accId)
                .orderByAsc("handle_time", "id"));
    }

    @Override
    public List<TIsAccUrge> listUrges(Long accId) {
        return urgeMapper.selectList(new QueryWrapper<TIsAccUrge>()
                .eq("acc_id", accId)
                .orderByAsc("urged_at", "id"));
    }

    @Override
    public Map<String, Object> stats(String username) {
        Map<String, Object> m = new HashMap<>();
        // 已作废不参与统计：status != 5。
        List<TIsAccFlow> live = flowMapper.selectList(new QueryWrapper<TIsAccFlow>()
                .eq("del_flag", 0)
                .ne("status", AccConst.ST_VOID));
        int reported = 0;
        int invest = 0;
        int determine = 0;
        int dispose = 0;
        int closed = 0;
        for (TIsAccFlow a : live) {
            int st = a.getStatus() == null ? -1 : a.getStatus();
            if (st == AccConst.ST_REPORTED) {
                reported++;
            } else if (st == AccConst.ST_INVEST) {
                invest++;
            } else if (st == AccConst.ST_DETERMINE) {
                determine++;
            } else if (st == AccConst.ST_DISPOSE) {
                dispose++;
            } else if (st == AccConst.ST_CLOSED) {
                closed++;
            }
        }
        m.put("reported", reported);
        m.put("invest", invest);
        m.put("determine", determine);
        m.put("dispose", dispose);
        m.put("closed", closed);
        m.put("myTodo", myTodo(username).size());
        return m;
    }

    @Override
    public UrgeScreen urgeScan() {
        return tx.urgeScan(activeChain(), new Date());
    }

    @Override
    public List<TIsAccLevel> listActiveLevels() {
        return activeChain().all();
    }

    @Override
    public List<TIsAccLevel> listAllLevels() {
        return levelMapper.selectList(new QueryWrapper<TIsAccLevel>()
                .eq("del_flag", 0)
                .orderByAsc("level_no", "id"));
    }

    @Override
    public TIsAccLevel addLevel(TIsAccLevel level) {
        return tx.addLevel(level, new Date());
    }

    @Override
    public TIsAccLevel retireLevel(Long id) {
        return tx.retireLevel(id, new Date());
    }

    /** 链条永远现取启用配置，不抄在单子上。 */
    private AccChain activeChain() {
        List<TIsAccLevel> levels = levelMapper.selectList(new QueryWrapper<TIsAccLevel>()
                .eq("del_flag", 0)
                .eq("status", 0)
                .orderByAsc("level_no"));
        return new AccChain(levels);
    }

    /** 当前登录用户名 → 用户 id → 持有的角色集合 → 可见层级号。 */
    private Set<Integer> visibleLevelNos(String username) {
        Set<Long> roleIds = new HashSet<>();
        Long userId = userIdByUsername(username);
        if (userId != null) {
            List<TSysRole> roles = roleService.queryUserRole(userId);
            if (roles != null) {
                for (TSysRole r : roles) {
                    roleIds.add(r.getId());
                }
            }
        }
        return activeChain().visibleLevelNos(roleIds);
    }

    private Long userIdByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        TSysUser u = userMapper.selectOne(new QueryWrapper<TSysUser>()
                .eq("username", username)
                .last("limit 1"));
        return u == null ? null : u.getId();
    }

    private TIsAccFlow mustGet(Long id) {
        TIsAccFlow a = flowMapper.selectById(id);
        if (a == null || (a.getDelFlag() != null && a.getDelFlag() == 1)) {
            throw new IllegalArgumentException("事故单不存在：" + id);
        }
        return a;
    }
}
