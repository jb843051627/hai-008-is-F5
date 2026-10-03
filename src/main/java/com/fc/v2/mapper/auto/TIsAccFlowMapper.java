package com.fc.v2.mapper.auto;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fc.v2.model.auto.TIsAccFlow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 事故报告调查处置单数据层。
 *
 * 所有流转都走"带前置条件的原子 UPDATE"：WHERE 里钉死在途状态、当前层级号、轮次号，
 * 更新 0 行即说明单子已被别人先推动（并发办理）或已不在这一层，调用方按失败处理。
 * 一张单同一时刻只挂在一级手上，不靠应用侧加锁，靠这条 SQL 兜底。
 *
 * 与整改签批的差别：单子状态跟着"层级所在路"走——挂到调查路的级是调查中，
 * 挂到认定路的级是已认定，挂到处置路的级是处置中；所以上报/办结推进/转交都显式带 toStatus。
 *
 * @author fuce
 * @date 2026-10-03
 */
public interface TIsAccFlowMapper extends BaseMapper<TIsAccFlow> {

    /**
     * 上报/补齐重报：进链条第一级，状态取第一级所在路的档。
     * resubmit=1 时轮次加一（补齐后从头轮算起）；首轮上报传 0。
     */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = #{toStatus}, cur_level_no = #{firstLevelNo}, "
            + " round_no = round_no + #{resubmit}, "
            + " report_time = #{at}, level_entered_at = #{at}, update_time = #{at} "
            + " WHERE id = #{id} AND status = 0 AND del_flag = 0")
    int report(@Param("id") Long id,
               @Param("firstLevelNo") int firstLevelNo,
               @Param("toStatus") int toStatus,
               @Param("resubmit") int resubmit,
               @Param("at") Date at);

    /** 办结且本层不是末级：进下一号层级，状态取新层级所在路的档，刷新进层时刻（催办重新计时）。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " cur_level_no = #{nextLevelNo}, status = #{toStatus}, "
            + " level_entered_at = #{at}, update_time = #{at} "
            + " WHERE id = #{id} AND status IN (1,2,3) AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int finishAdvance(@Param("id") Long id,
                      @Param("expectLevelNo") int expectLevelNo,
                      @Param("expectRound") int expectRound,
                      @Param("nextLevelNo") int nextLevelNo,
                      @Param("toStatus") int toStatus,
                      @Param("at") Date at);

    /** 末级办结即结案：结论=办结、结案时间同写，当前待办层级清空。结案写死，之后再无 UPDATE 能命中。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 4, conclusion = 1, closed_time = #{at}, "
            + " cur_level_no = NULL, update_time = #{at} "
            + " WHERE id = #{id} AND status IN (1,2,3) AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int finishClose(@Param("id") Long id,
                    @Param("expectLevelNo") int expectLevelNo,
                    @Param("expectRound") int expectRound,
                    @Param("at") Date at);

    /** 否决直接落已结案并写死结论=否决，流程自然终止；不许留在流程里。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 4, conclusion = 2, closed_time = #{at}, "
            + " cur_level_no = NULL, update_time = #{at} "
            + " WHERE id = #{id} AND status IN (1,2,3) AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int vetoClose(@Param("id") Long id,
                  @Param("expectLevelNo") int expectLevelNo,
                  @Param("expectRound") int expectRound,
                  @Param("at") Date at);

    /** 补充材料：退回上报人（已上报），当前待办层级清空；轮次不动，重报时才加一、重走第一级。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 0, cur_level_no = NULL, update_time = #{at} "
            + " WHERE id = #{id} AND status IN (1,2,3) AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int backToReporter(@Param("id") Long id,
                       @Param("expectLevelNo") int expectLevelNo,
                       @Param("expectRound") int expectRound,
                       @Param("at") Date at);

    /** 转交他级：本级意见已记全，单子改挂目标层级，状态随目标层级所在路走，进层时刻刷新；轮次不变。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " cur_level_no = #{targetLevelNo}, status = #{toStatus}, "
            + " level_entered_at = #{at}, update_time = #{at} "
            + " WHERE id = #{id} AND status IN (1,2,3) AND cur_level_no = #{expectLevelNo} "
            + " AND round_no = #{expectRound} AND del_flag = 0")
    int transferTo(@Param("id") Long id,
                   @Param("expectLevelNo") int expectLevelNo,
                   @Param("expectRound") int expectRound,
                   @Param("targetLevelNo") int targetLevelNo,
                   @Param("toStatus") int toStatus,
                   @Param("at") Date at);

    /**
     * 上报人撤回：只许对"还停在第一级、从未有任何一级办理过"的单翻成已作废。
     * 是否有级办理过由 Service 查留痕判；这里钉死仍挂在第一级，别人先接过手就翻不动。
     */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 5, update_time = #{at} "
            + " WHERE id = #{id} AND status = #{firstPhaseStatus} "
            + " AND cur_level_no = #{firstLevelNo} AND round_no = 1 AND del_flag = 0")
    int withdraw(@Param("id") Long id,
                 @Param("firstLevelNo") int firstLevelNo,
                 @Param("firstPhaseStatus") int firstPhaseStatus,
                 @Param("at") Date at);

    /** 管理员归档：只许对已结案的单翻成已作废；结论、结案时间不在 SET 里，谁都改不动。 */
    @Update("UPDATE t_is_acc_flow SET "
            + " status = 5, update_time = #{at} "
            + " WHERE id = #{id} AND status = 4 AND del_flag = 0")
    int archive(@Param("id") Long id, @Param("at") Date at);
}
