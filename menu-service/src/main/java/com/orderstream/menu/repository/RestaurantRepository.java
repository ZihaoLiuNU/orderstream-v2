package com.orderstream.menu.repository;

import com.orderstream.menu.entity.Restaurant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    /**
     * 分页返回在营餐厅。
     *
     * 【必须分页】—— 原版的 findAll() 是个定时炸弹：
     *   开发时只有 1 家餐厅，跑得飞快；生产上有 10 万家时，
     *   一次查询把 10 万行全部读进内存、全部序列化成 JSON，
     *   直接 OOM，而且这个 OOM 会拖垮整个 JVM 影响所有请求。
     *
     * 任何返回列表的接口都必须分页，没有例外。
     * 这是那种"上线前看不出问题、上线后必然出事"的典型。
     */
    Page<Restaurant> findByActiveTrue(Pageable pageable);
}
