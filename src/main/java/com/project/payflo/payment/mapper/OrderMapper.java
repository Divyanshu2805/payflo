package com.project.payflo.payment.mapper;

import com.project.payflo.payment.dto.response.OrderResponse;
import com.project.payflo.payment.entity.OrderRecord;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface OrderMapper {

    @Mapping(source = "orderStatus", target = "status")
    OrderResponse toResponse(OrderRecord orderRecord);
}
