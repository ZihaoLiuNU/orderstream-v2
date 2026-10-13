package com.orderstream.cart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Cart 限界上下文。
 *
 * ============================================================================
 * 【这是唯一一个不用关系数据库的服务 —— 这个选择本身就是设计的一部分】
 * ============================================================================
 *
 * 购物车数据的特征和订单完全不同：
 *   - 极高频读写：用户每加一道菜就是一次写
 *   - 天然易失：放弃的购物车不需要永久保留，行业数据是 70% 的购物车会被放弃
 *   - 弱一致性即可：少了一道菜用户自己会发现并重新加，不会造成实质损失
 *   - 有天然的生命周期：下单后就该清空，长期不动就该过期
 *
 * 用 Postgres 存它是杀鸡用牛刀：
 *   每次点"+1"都变成一次磁盘事务 + WAL 写入 + 可能的索引更新，
 *   而这个数据 70% 的概率根本不会变成订单。
 *
 * Redis 正好匹配：内存操作、原生支持 TTL、HINCRBY 原生原子自增。
 *
 * 【反过来说，订单【绝对不能】放 Redis】——
 *   订单是钱，必须持久、必须有事务、必须能对账。
 *   同一个系统里对不同数据用不同存储，不是技术炫技，
 *   而是因为它们的一致性/持久性要求本来就不同。
 *   这叫 Polyglot Persistence（多语言持久化），
 *   也是微服务相对单体的一个实质优势：单体只能用一个数据库，
 *   拆开之后每个服务可以选最适合自己的存储。
 *
 * ============================================================================
 * 【注意这里没有引入 messaging 模块】
 * ============================================================================
 *   messaging 依赖 spring-boot-starter-data-jpa。cart-service 没有关系数据库，
 *   引入它会触发 JPA 自动配置，然后因为找不到 DataSource 而启动失败。
 *
 *   而且 cart 也不需要 messaging 提供的两个能力：
 *     - 不需要 outbox：它不产生领域事件
 *     - 不需要幂等去重表：它唯一的消费动作（清空购物车）天然幂等
 *       （DELETE 一个 key，执行一次和执行十次结果完全相同）
 *
 *   这是个很好的例子：【不要因为"别的服务都有"就引入依赖】。
 *   每个依赖都是要维护、会出故障、会拖慢启动的。
 */
@SpringBootApplication(scanBasePackages = {
        "com.orderstream.cart",
        "com.orderstream.common"
})
public class CartServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }
}
