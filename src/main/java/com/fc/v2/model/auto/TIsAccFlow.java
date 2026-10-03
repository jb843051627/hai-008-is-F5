package com.fc.v2.model.auto;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.io.Serializable;
import java.util.Date;

/**
 * 事故报告调查处置单对象 t_is_acc_flow
 *
 * 一行一张，字段固定：事故单号、关联事故（哪起、什么等级）、上报人、上报时间、
 * 当前待办层级、状态、结论、结案时间。状态只在六档内取值，不许自造：
 * 0已上报 1调查中 2已认定 3处置中 4已结案 5已作废。
 *
 * @author fuce
 * @date 2026-10-03
 */
@TableName("t_is_acc_flow")
@ApiModel(value = "TIsAccFlow", description = "事故报告调查处置单")
public class TIsAccFlow implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 事故单号 */
    @TableField("flow_no")
    @ApiModelProperty(value = "事故单号")
    private String flowNo;

    /** 关联事故（哪起） */
    @TableField("accident_id")
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "关联事故")
    private Long accidentId;

    /** 关联事故号（抄存） */
    @TableField("accident_no")
    @ApiModelProperty(value = "关联事故号")
    private String accidentNo;

    /** 关联事故等级 1一般 2较大 3重大 4特别重大（落单后不许改） */
    @TableField("accident_level")
    @ApiModelProperty(value = "关联事故等级 1一般 2较大 3重大 4特别重大")
    private Integer accidentLevel;

    /** 上报人 */
    @TableField("reporter")
    @ApiModelProperty(value = "上报人")
    private String reporter;

    /** 最近一次上报时间（补材料重报刷新为重报时刻） */
    @TableField("report_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "上报时间")
    private Date reportTime;

    /** 当前待办层级（只此一个；退回上报人/结案后为空） */
    @TableField("cur_level_no")
    @ApiModelProperty(value = "当前待办层级")
    private Integer curLevelNo;

    /** 状态 0已上报 1调查中 2已认定 3处置中 4已结案 5已作废 */
    @TableField("status")
    @ApiModelProperty(value = "状态 0已上报 1调查中 2已认定 3处置中 4已结案 5已作废")
    private Integer status;

    /** 结论 1办结 2否决（结案写死，之前为空） */
    @TableField("conclusion")
    @ApiModelProperty(value = "结论 1办结 2否决")
    private Integer conclusion;

    /** 结案时间（结案写死，之前为空） */
    @TableField("closed_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "结案时间")
    private Date closedTime;

    /** 当前办理轮次（首轮1；每补材料重报一轮加一） */
    @TableField("round_no")
    @ApiModelProperty(value = "当前办理轮次")
    private Integer roundNo;

    /** 进入当前待办层级的时刻（催办计时起点） */
    @TableField("level_entered_at")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "进入当前待办层级的时刻")
    private Date levelEnteredAt;

    /** 删除标记 0正常 1删除 */
    @TableField("del_flag")
    @ApiModelProperty(value = "删除标记 0正常 1删除")
    private Integer delFlag;

    /** 创建者 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    /** 更新者 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    @ApiModelProperty(value = "更新者")
    private String updateBy;

    /** 更新时间 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "更新时间")
    private Date updateTime;

    /** 备注（上报事由/补齐说明） */
    @TableField("remark")
    @ApiModelProperty(value = "备注")
    private String remark;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFlowNo() {
        return flowNo;
    }

    public void setFlowNo(String flowNo) {
        this.flowNo = flowNo;
    }

    public Long getAccidentId() {
        return accidentId;
    }

    public void setAccidentId(Long accidentId) {
        this.accidentId = accidentId;
    }

    public String getAccidentNo() {
        return accidentNo;
    }

    public void setAccidentNo(String accidentNo) {
        this.accidentNo = accidentNo;
    }

    public Integer getAccidentLevel() {
        return accidentLevel;
    }

    public void setAccidentLevel(Integer accidentLevel) {
        this.accidentLevel = accidentLevel;
    }

    public String getReporter() {
        return reporter;
    }

    public void setReporter(String reporter) {
        this.reporter = reporter;
    }

    public Date getReportTime() {
        return reportTime;
    }

    public void setReportTime(Date reportTime) {
        this.reportTime = reportTime;
    }

    public Integer getCurLevelNo() {
        return curLevelNo;
    }

    public void setCurLevelNo(Integer curLevelNo) {
        this.curLevelNo = curLevelNo;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getConclusion() {
        return conclusion;
    }

    public void setConclusion(Integer conclusion) {
        this.conclusion = conclusion;
    }

    public Date getClosedTime() {
        return closedTime;
    }

    public void setClosedTime(Date closedTime) {
        this.closedTime = closedTime;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }

    public Date getLevelEnteredAt() {
        return levelEnteredAt;
    }

    public void setLevelEnteredAt(Date levelEnteredAt) {
        this.levelEnteredAt = levelEnteredAt;
    }

    public Integer getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(Integer delFlag) {
        this.delFlag = delFlag;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public String getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(String updateBy) {
        this.updateBy = updateBy;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
