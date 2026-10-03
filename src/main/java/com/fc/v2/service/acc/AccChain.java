package com.fc.v2.service.acc;

import com.fc.v2.model.auto.TIsAccLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启用层级链条的只读快照：次序永远现取配置（status=0 按 level_no 升序），不抄在事故单上。
 *
 * 事故单上报进 first()；办结进 nextOf(当前号)；nextOf 对末级返回 null（=末级办结结案）。
 * 在途状态档与层级在链条中的位次一一对应（0起）：办结到第 i+1 级时状态前进到 min(i+1, 3处置中)。
 *
 * @author fuce
 * @date 2026-10-03
 */
public class AccChain {

    private final List<TIsAccLevel> levels;

    public AccChain(List<TIsAccLevel> activeLevels) {
        List<TIsAccLevel> copy = new ArrayList<>(activeLevels);
        Collections.sort(copy, new Comparator<TIsAccLevel>() {
            @Override
            public int compare(TIsAccLevel a, TIsAccLevel b) {
                return Integer.compare(a.getLevelNo(), b.getLevelNo());
            }
        });
        this.levels = Collections.unmodifiableList(copy);
    }

    /** 链条一级都没配：上报无处可送。 */
    public boolean isEmpty() {
        return levels.isEmpty();
    }

    public int size() {
        return levels.size();
    }

    public List<TIsAccLevel> all() {
        return levels;
    }

    /** 第一级（上报/补齐重报都进这级）。 */
    public TIsAccLevel first() {
        return levels.isEmpty() ? null : levels.get(0);
    }

    /** 按层级号取启用层级；找不到（停用/没配）返回 null。 */
    public TIsAccLevel byNo(Integer levelNo) {
        if (levelNo == null) {
            return null;
        }
        for (TIsAccLevel l : levels) {
            if (l.getLevelNo().equals(levelNo)) {
                return l;
            }
        }
        return null;
    }

    /** 层级号在链条中的位次（0起）；找不到返回 -1。 */
    public int indexOfNo(Integer levelNo) {
        if (levelNo == null) {
            return -1;
        }
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).getLevelNo().equals(levelNo)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 当前号的下一号启用层级（比它大的最小号）；已是末级返回 null。
     * 注意：转交不走这里——转交可以去链条上任何别的启用级。
     */
    public TIsAccLevel nextOf(Integer levelNo) {
        TIsAccLevel hit = null;
        for (TIsAccLevel l : levels) {
            if (l.getLevelNo() > (levelNo == null ? Integer.MIN_VALUE : levelNo)) {
                hit = l;
                break;
            }
        }
        return hit;
    }

    /** 给定角色集合能办理的层级号集合：角色对不上的层级，单子连看都看不到。 */
    public Set<Integer> visibleLevelNos(Set<Long> roleIds) {
        Set<Integer> nos = new HashSet<>();
        if (roleIds == null || roleIds.isEmpty()) {
            return nos;
        }
        for (TIsAccLevel l : levels) {
            if (l.getHandlerRoleId() != null && roleIds.contains(l.getHandlerRoleId())) {
                nos.add(l.getLevelNo());
            }
        }
        return nos;
    }

    public Integer urgeHoursOf(Integer levelNo) {
        TIsAccLevel l = byNo(levelNo);
        if (l == null || l.getUrgeHours() == null || l.getUrgeHours() <= 0) {
            return AccConst.DEFAULT_URGE_HOURS;
        }
        return l.getUrgeHours();
    }
}
