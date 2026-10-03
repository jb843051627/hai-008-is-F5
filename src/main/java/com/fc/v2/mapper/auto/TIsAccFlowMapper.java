package com.fc.v2.mapper.auto;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TIsAccFlow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 事故报告调查处置单数据层。
 *
 * 所有流转都走"带前置条件的原子 UPDATE"：WHERE 里钉死当前状态/层级号/轮次号，
 * 更新 0 行即说明单子已被别人先推动（并发办理）或已不在这一层，调用方按失败处理。
 * 一张单同一时刻只挂在一级手上，不靠应用侧加锁，靠这条 SQL 兜底。
 *
 * 在途状态 0已上报/1调查中/2已认定/3处置中；办结沿链前进一档（状态+1，到处置中封顶），
 * 末级办结落 4已结案（结论=办结）；否决任一层直接落 4已结案（结论=否决），清空当前待办。
 *
 * @author fuce
 * @date 2026-10-03
 */
public interface TIsAccFlowMapper extends BaseMapper<TIsAccFlow> {

    /**
     * 补齐后重新上报：退回态（已上报、当前待办为空）→ 挂回链条第一级，轮次加一。
     * 首轮上报走 insert（见 AccFlowTx#report），不走这里。
     */
    @Update("UPDATE t_is_acc_flow SET "
            + " cur_level_no = #{firstLevelNo}, "
            + " round_no = round_no + 1, "
            + " report_time = #{at}, level_entered_at = #{at}, update_time = #{at} "
            + " WHERE id = #{id} AND status = 0 AND cur_level_no IS NULL "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int resubmit(@Param("id") Long id,
                 @Param("firstLevelNo") int firstLevelNo,
                 @Param("expectRound") int expectRound,
                 @Param("at") Date at);

    /** 办结且本层不是末级：进下一号层级，状态前进一档，刷新进层时刻（催办重新计时）。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = #{newStatus}, cur_level_no = #{nextLevelNo}, "
            + " level_entered_at = #{at}, update_time = #{at} "
            + " WHERE id = #{id} AND status = #{expectStatus} AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int completeAdvance(@Param("id") Long id,
                        @Param("expectStatus") int expectStatus,
                        @Param("expectLevelNo") int expectLevelNo,
                        @Param("expectRound") int expectRound,
                        @Param("nextLevelNo") int nextLevelNo,
                        @Param("newStatus") int newStatus,
                        @Param("at") Date at);

    /** 末级办结即结案：结论=办结、结案时间同写，当前待办清空。结案写死，之后再无 UPDATE 能命中。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 4, conclusion = 1, closed_time = #{at}, "
            + " cur_level_no = NULL, update_time = #{at} "
            + " WHERE id = #{id} AND status IN (0,1,2,3) AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int completeClose(@Param("id") Long id,
                      @Param("expectLevelNo") int expectLevelNo,
                      @Param("expectRound") int expectRound,
                      @Param("at") Date at);

    /** 否决任一层都直接结案（结论=否决）并清空当前待办；不许留在流程里。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 4, conclusion = 2, closed_time = #{at}, "
            + " cur_level_no = NULL, update_time = #{at} "
            + " WHERE id = #{id} AND status IN (0,1,2,3) AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int vetoClose(@Param("id") Long id,
                  @Param("expectLevelNo") int expectLevelNo,
                  @Param("expectRound") int expectRound,
                  @Param("at") Date at);

    /** 补充材料：退回上报人（已上报），当前待办清空；轮次不动，重报时才加一。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 0, cur_level_no = NULL, update_time = #{at} "
            + " WHERE id = #{id} AND status IN (0,1,2,3) AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int backToReporter(@Param("id") Long id,
                       @Param("expectLevelNo") int expectLevelNo,
                       @Param("expectRound") int expectRound,
                       @Param("at") Date at);

    /** 转交他级：本级意见已记全，单子改挂目标层级，进层时刻刷新；轮次、状态（阶段）不变。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " cur_level_no = #{targetLevelNo}, level_entered_at = #{at}, update_time = #{at} "
            + " WHERE id = #{id} AND status IN (0,1,2,3) AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int transferTo(@Param("id") Long id,
                   @Param("expectLevelNo") int expectLevelNo,
                   @Param("expectRound") int expectRound,
                   @Param("targetLevelNo") int targetLevelNo,
                   @Param("at") Date at);

    /** 上报人撤回：只许把"已上报、且从未产生任何层级办理记录"的单翻成已作废并清空当前待办（是否办过由 Service 查留痕判）。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 5, cur_level_no = NULL, update_time = #{at} "
            + " WHERE id = #{id} AND status = 0 AND del_flag = 0")
    int withdraw(@Param("id") Long id, @Param("at") Date at);

    /** 管理员归档：只许对已结案的单翻成已作废；结论、事故等级、结案时间不在 SET 里，谁都改不动。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 5, update_time = #{at} "
            + " WHERE id = #{id} AND status = 4 AND del_flag = 0")
    int archive(@Param("id") Long id, @Param("at") Date at);
}
