package com.project.payflo.vault.repository;

import com.project.payflo.vault.entity.CardToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CardTokenRepository extends JpaRepository<CardToken, UUID> {

}
