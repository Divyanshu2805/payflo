package com.project.payflo.merchant.mapper;

import com.project.payflo.merchant.dto.response.ApiKeyCreateResponse;
import com.project.payflo.merchant.dto.response.ApiKeyResponse;
import com.project.payflo.merchant.entity.ApiKey;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ApiKeyMapper {

    @Mapping(source = "keySecretHash", target = "keySecret")
    ApiKeyCreateResponse toCreateResponse(ApiKey apiKey);

    List<ApiKeyResponse> toResponseList(List<ApiKey> apiKeyList);
}
