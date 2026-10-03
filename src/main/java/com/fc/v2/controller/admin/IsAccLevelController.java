package com.fc.v2.controller.admin;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TIsAccLevel;
import com.fc.v2.service.ITIsAccFlowService;
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
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * 事故调查处置办理层级配置 Controller。
 *
 * 层级次序与所属"路"由平台写死、不抄在单子上：登记层级、停用（有在途单拒停）。链条对流转实时生效。
 *
 * @author fuce
 * @date 2026-10-03
 */
@Api(value = "事故调查处置办理层级配置")
@Controller
@RequestMapping("/isAccLevel")
public class IsAccLevelController extends BaseController {

    private final String prefix = "admin/isAccLevel";

    @Autowired
    private ITIsAccFlowService accFlowService;

    @ApiOperation(value = "层级配置跳转", notes = "层级配置跳转")
    @GetMapping("/view")
    @RequiresPermissions("isAccLevel:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "事故办理层级配置查询", action = "list")
    @ApiOperation(value = "层级全量", notes = "启用停用都翻得到；流转只认 status=0")
    @GetMapping("/list")
    @RequiresPermissions("isAccLevel:list")
    @ResponseBody
    public ResultTable list() {
        startPage();
        List<TIsAccLevel> list = accFlowService.listAllLevels();
        com.github.pagehelper.PageInfo<TIsAccLevel> page = new com.github.pagehelper.PageInfo<>(list);
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "事故办理层级登记", action = "add")
    @ApiOperation(value = "登记一级", notes = "次序不许重号；必须挂一条路并绑定本层办理角色（角色对不上这层的单看不到）")
    @PostMapping("/add")
    @RequiresPermissions("isAccLevel:add")
    @RequiresRoles("管理员")
    @ResponseBody
    public AjaxResult add(TIsAccLevel level) {
        try {
            TIsAccLevel saved = accFlowService.addLevel(level);
            return AjaxResult.success("第" + saved.getLevelNo() + "级[" + saved.getLevelName() + "]已登记")
                    .put("data", saved);
        } catch (RuntimeException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }

    @Log(title = "事故办理层级停用", action = "retire")
    @ApiOperation(value = "停用一级", notes = "有在途单挂在这级时拒停")
    @PostMapping("/retire")
    @RequiresPermissions("isAccLevel:add")
    @RequiresRoles("管理员")
    @ResponseBody
    public AjaxResult retire(Long id) {
        try {
            TIsAccLevel saved = accFlowService.retireLevel(id);
            return AjaxResult.success("第" + saved.getLevelNo()
                    + "级已停用，新上报的单不再经过这级（在途单此前已拦在停用前）").put("data", saved);
        } catch (RuntimeException ex) {
            return AjaxResult.error(ex.getMessage());
        }
    }
}
