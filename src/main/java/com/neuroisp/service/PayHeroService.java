package com.neuroisp.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.neuroisp.entity.PaymentGateway;
import com.neuroisp.entity.UserSubscription;
import com.neuroisp.repository.PaymentGatewayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PayHeroService {

    private final PaymentGatewayRepository gatewayRepo;

    public String sendStkPush(UserSubscription sub) {

        PaymentGateway gateway = gatewayRepo.findByActiveTrue()
                .orElseThrow(() -> new RuntimeException("No active PayHero gateway"));

        int amount = (int) sub.getInternetPackage().getPrice();
        // ✅ PACKAGE PRICE SOURCE

        try {
            HttpClient client = HttpClient.newHttpClient();

            Map<String, Object> payload = new HashMap<>();
            payload.put("amount", amount);
            payload.put("phone_number", sub.getPhoneNumber());
            payload.put("channel_id", gateway.getChannelId());
            payload.put("provider", gateway.getProvider());
            payload.put("external_reference", sub.getId()); // Subscription ID
            payload.put("customer_name", sub.getPhoneNumber());

            payload.put("callback_url", "https://astonishing-playfulness-production.up.railway.app/api/payhero/callback");

            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gateway.getApiUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Basic " + gateway.getAuthorizationToken())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.body();

        } catch (Exception e) {
            throw new RuntimeException("PayHero STK push failed", e);
        }
    }
}
