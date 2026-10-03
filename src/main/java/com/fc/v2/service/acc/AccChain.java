package com.fc.v2.service.acc;

import com.fc.v2.model.auto.TIsAccLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启用层级链条的只读快照：次序永远现取配置（status=0 按 level_no 升序），不抄在单子上。
 *
 * 单子上报进 first()；办结进 nextOf(当前号)；nextOf 对末级返回 null（=末级办结结案）。
 * 单子挂在哪一级，状态就是那一级 phase_no 对应的路档（1调查中 2已认定 3处置中）。
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

    /** 链条一级都没配：上报无处可去。 */
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
            if (l.getSignerRoleId() != null && roleIds.contains(l.getSignerRoleId())) {
                nos.add(l.getLevelNo());
            }
        }
        return nos;
    }

    /**
     * 层级所在"路"对应的单子状态档：phase_no 即状态档（1调查中 2已认定 3处置中）。
     * 平台配错（phase_no 越界）时直接抛——状态只六档，不许被错误配置逼出自造值。
     */
    public int statusOf(TIsAccLevel level) {
        if (level == null || level.getPhaseNo() == null
                || level.getPhaseNo() < 1 || level.getPhaseNo() > 3) {
            throw new IllegalStateException("层级[" + (level == null ? null : level.getLevelName())
                    + "]没挂到合法的路上（phase_no 只许 1调查 2认定 3处置），平台配置补正后再走");
        }
        return level.getPhaseNo();
    }

    public Integer urgeHoursOf(Integer levelNo) {
        TIsAccLevel l = byNo(levelNo);
        if (l == null || l.getUrgeHours() == null || l.getUrgeHours() <= 0) {
            return AccConst.DEFAULT_URGE_HOURS;
        }
        return l.getUrgeHours();
    }
}
