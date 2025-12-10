package com.project.payflo.merchant.service;

import com.project.payflo.merchant.entity.AppUser;

public interface RefreshTokenService {

    String issue(AppUser appUser);

    AppUser rotate(String rawToken);

    void revoke(String rawToken);
}
