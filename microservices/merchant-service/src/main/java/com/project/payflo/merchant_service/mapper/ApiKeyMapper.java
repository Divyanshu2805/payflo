package com.project.payflo.merchant_service.mapper;

import com.project.payflo.merchant_service.dto.response.ApiKeyCreateResponse;
import com.project.payflo.merchant_service.dto.response.ApiKeyResponse;
import com.project.payflo.merchant_service.entity.ApiKey;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ApiKeyMapper {

    // The secret is shown once, by the service that creates it: the entity only has its hash.
    @Mapping(target = "keySecret", ignore = true)
    ApiKeyCreateResponse toCreateResponse(ApiKey apiKey);

    List<ApiKeyResponse> toResponseList(List<ApiKey> apiKeyList);
}
