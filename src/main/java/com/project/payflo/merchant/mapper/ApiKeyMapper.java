package com.project.payflo.merchant.mapper;

import com.project.payflo.merchant.dto.response.ApiKeyResponse;
import com.project.payflo.merchant.entity.ApiKey;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ApiKeyMapper {

    List<ApiKeyResponse> toResponseList(List<ApiKey> apiKeyList);
}
