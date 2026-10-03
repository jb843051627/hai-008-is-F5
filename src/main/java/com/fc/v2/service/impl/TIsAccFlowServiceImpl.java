package com.fc.v2.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TIsAccFlowMapper;
import com.fc.v2.mapper.auto.TIsAccHandleMapper;
import com.fc.v2.mapper.auto.TIsAccLevelMapper;
import com.fc.v2.mapper.auto.TIsAccUrgeMapper;
import com.fc.v2.mapper.auto.TSysUserMapper;
import com.fc.v2.model.auto.TIsAccFlow;
import com.fc.v2.model.auto.TIsAccHandle;
import com.fc.v2.model.auto.TIsAccLevel;
import com.fc.v2.model.auto.TIsAccUrge;
import com.fc.v2.model.auto.TSysRole;
import com.fc.v2.model.auto.TSysUser;
import com.fc.v2.service.ITIsAccFlowService;
import com.fc.v2.service.ITSysRoleService;
import com.fc.v2.service.acc.AccChain;
import com.fc.v2.service.acc.AccConst;
import com.fc.v2.service.acc.AccFlowTx;
import com.fc.v2.service.acc.AccUrgeScreen;
import com.fc.v2.service.acc.HandleCmd;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事故报告调查处置逐级流转 Service 实现。
 *
 * 职责切分（同 TIsRectApplyServiceImpl 的范式）：本类做链条现取、角色可见性、台账点算；
 * 真正落库的每一步流转都在 {@link AccFlowTx} 的单一事务里。
 *
 * @author fuce
 * @date 2026-10-03
 */
@Service
public class TIsAccFlowServiceImpl implements ITIsAccFlowService {

    @Resource
    private TIsAccFlowMapper flowMapper;
    @Resource
    private TIsAccHandleMapper handleMapper;
    @Resource
    private TIsAccUrgeMapper urgeMapper;
    @Resource
    private TIsAccLevelMapper levelMapper;
    @Resource
    private ITSysRoleService roleService;
    @Resource
    private TSysUserMapper userMapper;
    @Resource
    private AccFlowTx tx;

    @Override
    public TIsAccFlow report(Long accidentId, String remark, String reporter) {
        return tx.report(accidentId, remark, reporter, activeChain(), new Date());
    }

    @Override
    public TIsAccFlow resubmit(Long id, String supplementNote, String reporter) {
        return tx.resubmit(id, supplementNote, reporter, activeChain(), new Date());
    }

    @Override
    public TIsAccFlow handle(HandleCmd cmd) {
        TIsAccFlow f = mustGet(cmd.getId());
        // 不是自己这层的单，连办理都不许——与"连看都看不到"同一道墙：角色对不上直接拒。
        AccChain chain = activeChain();
        TIsAccLevel cur = chain.byNo(f.getCurLevelNo());
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
        return tx.withdraw(id, reporter, activeChain(), new Date());
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
    public List<TIsAccFlow> listLedger(Integer status, String flowNo) {
        QueryWrapper<TIsAccFlow> qw = new QueryWrapper<TIsAccFlow>().eq("del_flag", 0);
        if (status != null) {
            qw.eq("status", status);
        }
        if (flowNo != null && !flowNo.trim().isEmpty()) {
            qw.likeRight("flow_no", flowNo.trim());
        }
        qw.orderByDesc("create_time", "id");
        return flowMapper.selectList(qw);
    }

    @Override
    public List<TIsAccFlow> myTodo(String username) {
        // 入参在 Controller 已是登录本人；这里按用户名找到其角色，再按角色放层级。
        Set<Integer> visibleNos = visibleLevelNos(username);
        if (visibleNos.isEmpty()) {
            return new ArrayList<>();
        }
        return flowMapper.selectList(new QueryWrapper<TIsAccFlow>()
                .eq("del_flag", 0)
                .in("status", Arrays.asList(
                        AccConst.ST_INVESTIGATING, AccConst.ST_DETERMINED, AccConst.ST_DISPOSING))
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
        data.put("handles", listHandles(id));
        data.put("urges", listUrges(id));
        // "现在停在哪一级、谁该办"：把当前待办层级与它的办理角色一并给出。
        data.put("curLevel", activeChain().byNo(flow.getCurLevelNo()));
        return data;
    }

    @Override
    public List<TIsAccHandle> listHandles(Long flowId) {
        return handleMapper.selectList(new QueryWrapper<TIsAccHandle>()
                .eq("flow_id", flowId)
                .orderByAsc("handle_time", "id"));
    }

    @Override
    public List<TIsAccUrge> listUrges(Long flowId) {
        return urgeMapper.selectList(new QueryWrapper<TIsAccUrge>()
                .eq("flow_id", flowId)
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
        int investigating = 0;
        int determined = 0;
        int disposing = 0;
        int finished = 0;
        int vetoed = 0;
        for (TIsAccFlow f : live) {
            int st = f.getStatus() == null ? -1 : f.getStatus();
            if (st == AccConst.ST_REPORTED) {
                reported++;
            } else if (st == AccConst.ST_INVESTIGATING) {
                investigating++;
            } else if (st == AccConst.ST_DETERMINED) {
                determined++;
            } else if (st == AccConst.ST_DISPOSING) {
                disposing++;
            } else if (st == AccConst.ST_CLOSED) {
                // 已结案里再按结论分出办结/否决两笔。
                if (f.getConclusion() != null && f.getConclusion() == AccConst.CON_VETO) {
                    vetoed++;
                } else {
                    finished++;
                }
            }
        }
        m.put("reported", reported);
        m.put("investigating", investigating);
        m.put("determined", determined);
        m.put("disposing", disposing);
        m.put("finished", finished);
        m.put("vetoed", vetoed);
        m.put("myTodo", myTodo(username).size());
        return m;
    }

    @Override
    public AccUrgeScreen urgeScan() {
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
        if (level.getLevelNo() == null || level.getLevelNo() < 1) {
            throw new IllegalArgumentException("层级次序必须是不小于 1 的整数");
        }
        if (level.getLevelName() == null || level.getLevelName().trim().isEmpty()) {
            throw new IllegalArgumentException("层级名不能为空");
        }
        if (level.getPhaseNo() == null || level.getPhaseNo() < 1 || level.getPhaseNo() > 3) {
            throw new IllegalArgumentException("每一级必须挂一条路：phase_no 只许 1调查 2认定 3处置");
        }
        if (level.getSignerRoleId() == null) {
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
        level.setCreateTime(new Date());
        level.setUpdateTime(new Date());
        levelMapper.insert(level);
        return level;
    }

    @Override
    public TIsAccLevel retireLevel(Long id) {
        TIsAccLevel level = levelMapper.selectById(id);
        if (level == null || (level.getDelFlag() != null && level.getDelFlag() == 1)) {
            throw new IllegalArgumentException("层级不存在：" + id);
        }
        // 有在途单还挂在这级，不许停用：停用会让这些单再也走不动、也没人看得到。
        long onTheWay = flowMapper.selectCount(new QueryWrapper<TIsAccFlow>()
                .eq("del_flag", 0)
                .in("status", Arrays.asList(
                        AccConst.ST_INVESTIGATING, AccConst.ST_DETERMINED, AccConst.ST_DISPOSING))
                .eq("cur_level_no", level.getLevelNo()));
        if (onTheWay > 0) {
            throw new IllegalStateException("还有 " + onTheWay + " 张在途单挂在[" + level.getLevelName()
                    + "]，处理完才能停用这一级");
        }
        level.setStatus(1);
        level.setUpdateTime(new Date());
        levelMapper.updateById(level);
        return level;
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
        TIsAccFlow f = flowMapper.selectById(id);
        if (f == null || (f.getDelFlag() != null && f.getDelFlag() == 1)) {
            throw new IllegalArgumentException("处置单不存在：" + id);
        }
        return f;
    }
}
