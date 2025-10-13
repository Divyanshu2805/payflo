package com.project.payflo.merchant.mapper;

import com.project.payflo.merchant.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant.dto.response.MerchantResponse;
import com.project.payflo.merchant.entity.Merchant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MerchantMapper {

    Merchant toEntityFromSignUpRequest(MerchantSignupRequest request);

    @Mapping(source = "status", target = "merchantStatus")
    MerchantResponse toResponse(Merchant merchant);
}
