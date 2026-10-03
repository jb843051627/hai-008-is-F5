package com.fc.v2.model.auto;

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
 * 事故调查处置办理意见留痕对象 t_is_acc_handle
 *
 * 一张单多条，按轮次+层级+时刻排：每一级的意见、办理人、办理时间都在这，一查就有。
 * 代办时实际落笔者 handler、原办理人 intended_handler、代办原因 agent_reason 三栏写全。
 *
 * @author fuce
 * @date 2026-10-03
 */
@TableName("t_is_acc_handle")
@ApiModel(value = "TIsAccHandle", description = "事故调查处置办理意见留痕")
public class TIsAccHandle implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "主键")
    private Long id;

    /** 所属处置单 */
    @TableField("flow_id")
    @JsonSerialize(using = ToStringSerializer.class)
    @ApiModelProperty(value = "所属处置单")
    private Long flowId;

    /** 事故单号（抄存） */
    @TableField("flow_no")
    @ApiModelProperty(value = "事故单号")
    private String flowNo;

    /** 本轮办理轮次（重报后在新轮次里重走第一级） */
    @TableField("round_no")
    @ApiModelProperty(value = "办理轮次")
    private Integer roundNo;

    /** 意见所属层级（流程事件为空） */
    @TableField("level_no")
    @ApiModelProperty(value = "意见所属层级")
    private Integer levelNo;

    /** 层级名（抄存） */
    @TableField("level_name")
    @ApiModelProperty(value = "层级名")
    private String levelName;

    /** 动作 REPORT/FINISH/VETO/SUPPLEMENT/TRANSFER/RESUBMIT/WITHDRAW/ARCHIVE */
    @TableField("action")
    @ApiModelProperty(value = "动作")
    private String action;

    /** 实际落笔者（代办时即代办人） */
    @TableField("handler")
    @ApiModelProperty(value = "实际落笔者")
    private String handler;

    /** 原办理人（代办时与落笔者不同；正常办理两者相同） */
    @TableField("intended_handler")
    @ApiModelProperty(value = "原办理人")
    private String intendedHandler;

    /** 是否代办 0否 1是 */
    @TableField("agent_flag")
    @ApiModelProperty(value = "是否代办 0否 1是")
    private Integer agentFlag;

    /** 代办原因（代办必填） */
    @TableField("agent_reason")
    @ApiModelProperty(value = "代办原因")
    private String agentReason;

    /** 转交目标层级号（仅转交） */
    @TableField("target_level_no")
    @ApiModelProperty(value = "转交目标层级号")
    private Integer targetLevelNo;

    /** 转交目标层级名（仅转交） */
    @TableField("target_level_name")
    @ApiModelProperty(value = "转交目标层级名")
    private String targetLevelName;

    /** 本层处理意见（办理/转交必填，转交须把本级意见记全） */
    @TableField("comment")
    @ApiModelProperty(value = "处理意见")
    private String comment;

    /** 办理/事件时刻 */
    @TableField("handle_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "办理时刻")
    private Date handleTime;

    /** 创建者 */
    @TableField("create_by")
    @ApiModelProperty(value = "创建者")
    private String createBy;

    /** 创建时间 */
    @TableField("create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @ApiModelProperty(value = "创建时间")
    private Date createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFlowId() {
        return flowId;
    }

    public void setFlowId(Long flowId) {
        this.flowId = flowId;
    }

    public String getFlowNo() {
        return flowNo;
    }

    public void setFlowNo(String flowNo) {
        this.flowNo = flowNo;
    }

    public Integer getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(Integer roundNo) {
        this.roundNo = roundNo;
    }

    public Integer getLevelNo() {
        return levelNo;
    }

    public void setLevelNo(Integer levelNo) {
        this.levelNo = levelNo;
    }

    public String getLevelName() {
        return levelName;
    }

    public void setLevelName(String levelName) {
        this.levelName = levelName;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getHandler() {
        return handler;
    }

    public void setHandler(String handler) {
        this.handler = handler;
    }

    public String getIntendedHandler() {
        return intendedHandler;
    }

    public void setIntendedHandler(String intendedHandler) {
        this.intendedHandler = intendedHandler;
    }

    public Integer getAgentFlag() {
        return agentFlag;
    }

    public void setAgentFlag(Integer agentFlag) {
        this.agentFlag = agentFlag;
    }

    public String getAgentReason() {
        return agentReason;
    }

    public void setAgentReason(String agentReason) {
        this.agentReason = agentReason;
    }

    public Integer getTargetLevelNo() {
        return targetLevelNo;
    }

    public void setTargetLevelNo(Integer targetLevelNo) {
        this.targetLevelNo = targetLevelNo;
    }

    public String getTargetLevelName() {
        return targetLevelName;
    }

    public void setTargetLevelName(String targetLevelName) {
        this.targetLevelName = targetLevelName;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Date getHandleTime() {
        return handleTime;
    }

    public void setHandleTime(Date handleTime) {
        this.handleTime = handleTime;
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
}
