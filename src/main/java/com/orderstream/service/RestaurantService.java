package com.orderstream.service;

import com.orderstream.dto.MenuItemResponse;
import com.orderstream.dto.RestaurantResponse;
import com.orderstream.exception.ResourceNotFoundException;
import com.orderstream.repository.MenuItemRepository;
import com.orderstream.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;

    @Transactional(readOnly = true)
    public List<RestaurantResponse> getAllRestaurants() {
        return restaurantRepository.findAll().stream()
                .map(RestaurantResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RestaurantResponse getRestaurantWithMenu(Long id) {
        var restaurant = restaurantRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found: " + id));

        List<MenuItemResponse> menuItems = menuItemRepository.findByRestaurantId(id).stream()
                .map(MenuItemResponse::from)
                .collect(Collectors.toList());

        return RestaurantResponse.from(restaurant, menuItems);
    }
}
