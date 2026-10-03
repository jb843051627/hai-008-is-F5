package com.fc.v2.controller.admin;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TIsAccFlow;
import com.fc.v2.service.ITIsAccFlowService;
import com.fc.v2.service.acc.AccUrgeScreen;
import com.fc.v2.service.acc.HandleCmd;
import com.fc.v2.shiro.util.ShiroUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.authz.annotation.RequiresRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事故报告调查处置逐级流转 Controller。
 *
 * 一张单一行：停在哪一级、谁该办、已有哪些意见，台账与明细一翻便知。
 * 每级只看得到轮到自己这层的单；作废只两入口（上报人进级未办前撤回 / 管理员结案后归档）。
 *
 * @author fuce
 * @date 2026-10-03
 */
@Api(value = "事故报告调查处置逐级流转")
@Controller
@RequestMapping("/isAccFlow")
public class IsAccFlowController extends BaseController {

    private final String prefix = "admin/isAccFlow";

    @Autowired
    private ITIsAccFlowService accFlowService;

    @ApiOperation(value = "流转台账跳转", notes = "流转台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("isAccFlow:view")
    public String view(ModelMap model) {
        model.put("levels", accFlowService.listActiveLevels());
        return prefix + "/list";
    }

    @Log(title = "事故调查处置流转台账", action = "list")
    @ApiOperation(value = "台账分页", notes = "作废单仍翻得到，只是不进待办与统计")
    @GetMapping("/list")
    @RequiresPermissions("isAccFlow:list")
    @ResponseBody
    public ResultTable list(@RequestParam(required = false) Integer status,
                            @RequestParam(required = false) String flowNo) {
        startPage();
        List<TIsAccFlow> list = accFlowService.listLedger(status, flowNo);
        com.github.pagehelper.PageInfo<TIsAccFlow> page = new com.github.pagehelper.PageInfo<>(list);
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "我的待办", action = "todo")
    @ApiOperation(value = "我的待办", notes = "只返回当前待办层级在本人办理角色内的在途单，不是自己这层的看不到")
    @GetMapping("/myTodo")
    @RequiresPermissions("isAccFlow:list")
    @ResponseBody
    public ResultTable myTodo() {
        startPage();
        List<TIsAccFlow> list = accFlowService.myTodo(ShiroUtils.getLoginName());
        com.github.pagehelper.PageInfo<TIsAccFlow> page = new com.github.pagehelper.PageInfo<>(list);
        return pageTable(page.getList(), page.getTotal());
    }

    @ApiOperation(value = "我上报的单", notes = "含补材料退回")
    @GetMapping("/myReported")
    @RequiresPermissions("isAccFlow:list")
    @ResponseBody
    public ResultTable myReported() {
        startPage();
        List<TIsAccFlow> list = accFlowService.myReported(ShiroUtils.getLoginName());
        com.github.pagehelper.PageInfo<TIsAccFlow> page = new com.github.pagehelper.PageInfo<>(list);
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "事故处置单明细", action = "detail")
    @ApiOperation(value = "单张全貌", notes = "单子当前各栏 + 逐级办理意见 + 催办留痕")
    @GetMapping("/detail")
    @RequiresPermissions("isAccFlow:list")
    @ResponseBody
    public AjaxResult detail(Long id) {
        try {
            return AjaxResult.successData(200, accFlowService.detail(id));
        } catch (RuntimeException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    @ApiOperation(value = "点算", notes = "已作废不参与；含本人待办数")
    @GetMapping("/stats")
    @RequiresPermissions("isAccFlow:list")
    @ResponseBody
    public AjaxResult stats() {
        Map<String, Object> m = new HashMap<>(accFlowService.stats(ShiroUtils.getLoginName()));
        return AjaxResult.successData(200, m);
    }

    @Log(title = "事故报告上报", action = "report")
    @ApiOperation(value = "上报事故单", notes = "上报即进第一级；关联事故（哪起、什么等级，等级取事故台账）落单后不许改")
    @PostMapping("/report")
    @RequiresPermissions("isAccFlow:report")
    @ResponseBody
    public AjaxResult report(Long accidentId, String remark) {
        try {
            TIsAccFlow saved = accFlowService.report(accidentId, remark, ShiroUtils.getLoginName());
            return AjaxResult.success("事故单[" + saved.getFlowNo() + "]已上报，当前待办：第"
                    + saved.getCurLevelNo() + "级").put("data", saved);
        } catch (RuntimeException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    @Log(title = "补齐材料重新上报", action = "resubmit")
    @ApiOperation(value = "补齐重报", notes = "补材料后重报轮次加一、从第一级重头走")
    @PostMapping("/resubmit")
    @RequiresPermissions("isAccFlow:report")
    @ResponseBody
    public AjaxResult resubmit(Long id, String supplementNote) {
        try {
            TIsAccFlow saved = accFlowService.resubmit(id, supplementNote, ShiroUtils.getLoginName());
            return AjaxResult.success("已重新上报，当前待办：第" + saved.getCurLevelNo() + "级")
                    .put("data", saved);
        } catch (RuntimeException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    @Log(title = "事故处置层级办理", action = "handle")
    @ApiOperation(value = "层级办理", notes = "办结/否决/补充材料/转交他级；转交须写全本级意见；代办写原办理人与原因")
    @PostMapping("/handle")
    @RequiresPermissions("isAccFlow:handle")
    @ResponseBody
    public AjaxResult handle(Long id, String action, String comment,
                             String intendedHandler, String agentReason, Integer targetLevelNo) {
        HandleCmd cmd = new HandleCmd();
        cmd.setId(id);
        cmd.setAction(action);
        cmd.setComment(comment);
        cmd.setOperator(ShiroUtils.getLoginName());
        cmd.setIntendedHandler(intendedHandler);
        cmd.setAgentReason(agentReason);
        cmd.setTargetLevelNo(targetLevelNo);
        try {
            TIsAccFlow saved = accFlowService.handle(cmd);
            String msg;
            if (saved.getStatus() != null && saved.getStatus() == 4) {
                msg = "流程结案，结论：" + (saved.getConclusion() != null
                        && saved.getConclusion() == 1 ? "办结" : "否决");
            } else if (saved.getStatus() != null && saved.getStatus() == 0) {
                msg = "已退回上报人补充材料，补齐后从第一级重新上报";
            } else {
                msg = "已办理，当前待办：第" + saved.getCurLevelNo() + "级";
            }
            return AjaxResult.success(msg).put("data", saved);
        } catch (RuntimeException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    @Log(title = "事故单撤回", action = "withdraw")
    @ApiOperation(value = "上报人撤回作废", notes = "只许在单子还没进任何一级办理前（仍挂第一级且无层级办理记录）")
    @PostMapping("/withdraw")
    @RequiresPermissions("isAccFlow:report")
    @ResponseBody
    public AjaxResult withdraw(Long id) {
        try {
            accFlowService.withdraw(id, ShiroUtils.getLoginName());
            return AjaxResult.success("已撤回作废");
        } catch (RuntimeException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    @Log(title = "结案事故单归档", action = "archive")
    @ApiOperation(value = "管理员归档作废", notes = "只许对已结案单；结论与结案时间原样封死，谁都改不了")
    @PostMapping("/archive")
    @RequiresPermissions("isAccFlow:archive")
    @RequiresRoles("管理员")
    @ResponseBody
    public AjaxResult archive(Long id) {
        try {
            accFlowService.archive(id, ShiroUtils.getLoginName());
            return AjaxResult.success("已归档作废，结论与结案时间原样保留");
        } catch (RuntimeException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    @Log(title = "事故处置超时催办扫描", action = "urge")
    @ApiOperation(value = "立即催办一轮", notes = "只催在途超时单；同轮同级只催一次；作废/结案/退回上报人扫不到")
    @PostMapping("/urge")
    @RequiresPermissions("isAccFlow:urge")
    @ResponseBody
    public AjaxResult urge() {
        AccUrgeScreen screen = accFlowService.urgeScan();
        return AjaxResult.success(screen.getMessage()).put("data", screen);
    }
}
