package com.project.payflo.merchant_service.mapper;

import com.project.payflo.merchant_service.dto.request.MerchantSignupRequest;
import com.project.payflo.merchant_service.dto.response.MerchantResponse;
import com.project.payflo.merchant_service.entity.Merchant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MerchantMapper {

    // A signup carries the name, email, business name and type; everything else is set later or by the server (the
    // status is forced to PENDING_KYC by MerchantRegistrar), so none of it may come from the request.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "contactNumber", ignore = true)
    @Mapping(target = "websiteUrl", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "suspendedAt", ignore = true)
    @Mapping(target = "suspensionReason", ignore = true)
    @Mapping(target = "statusBeforeSuspension", ignore = true)
    @Mapping(target = "gstId", ignore = true)
    @Mapping(target = "panId", ignore = true)
    @Mapping(target = "settlementBankAccount", ignore = true)
    @Mapping(target = "settlementBankIfsc", ignore = true)
    @Mapping(target = "settlementBankAccountHolderName", ignore = true)
    Merchant toEntityFromSignUpRequest(MerchantSignupRequest request);

    @Mapping(source = "status", target = "merchantStatus")
    MerchantResponse toResponse(Merchant merchant);
}
