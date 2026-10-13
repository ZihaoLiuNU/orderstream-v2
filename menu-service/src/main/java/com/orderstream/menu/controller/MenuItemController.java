package com.orderstream.menu.controller;

import com.orderstream.menu.dto.MenuItemResponse;
import com.orderstream.menu.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 菜品批量查询 —— 主要供 order-service 下单时调用。
 *
 * 【这是一个"内部接口"，但它并没有特殊的保护机制】
 *   没有 API Key、没有 mTLS、没有单独的网络策略。
 *   保护它的是：order-service 通过 Docker 内网直连 menu-service:8080，
 *   而 menu-service 的端口不对外暴露。
 *
 *   这个"靠网络隔离"的方案在生产环境是不够的 ——
 *   任何一个被攻破的内部服务都能随意调用它。
 *   完整方案是 service mesh 的 mTLS：每个服务有自己的证书，
 *   服务间调用双向验证身份，并用授权策略限定"谁能调谁"。
 *   这叫零信任(zero trust)：不因为"在内网"就信任任何请求。
 *
 *   我这里没做，是因为上 Istio/Linkerd 的运维成本远超这个项目的收益。
 *   但边界在哪里必须清楚。
 */
@RestController
@RequestMapping("/api/menu-items")
@RequiredArgsConstructor
public class MenuItemController {

    private final MenuService menuService;

    /**
     * 批量按 ID 查询。
     *
     * 用 GET + query 参数而不是 POST + body：
     *   这是个纯查询，语义上应该是 GET —— GET 是幂等且可缓存的，
     *   中间件（网关、CDN、浏览器）能据此做正确的优化和重试。
     *   用 POST 做查询会让网关的"只重试 GET"策略失效。
     *
     * 代价：URL 长度有限制（大多数服务器 8KB）。
     *   几百个 ID 没问题，上千个就得改用 POST。
     *   所以 MenuService 里限制了单次最多 200 个。
     */
    @GetMapping
    public List<MenuItemResponse> getByIds(@RequestParam("ids") Set<Long> ids) {
        return menuService.getMenuItemsByIds(ids);
    }
}
