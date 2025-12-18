package com.project.payflo.merchant.mapper;

import com.project.payflo.merchant.dto.response.WebhookConfigResponse;
import com.project.payflo.merchant.entity.MerchantWebhookConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface WebhookConfigMapper {

    @Mapping(target = "webhookSecret", source = "rawSecret")
    WebhookConfigResponse toResponse(MerchantWebhookConfig merchantWebhookConfig, String rawSecret);

}
