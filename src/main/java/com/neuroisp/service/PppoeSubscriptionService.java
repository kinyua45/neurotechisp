package com.neuroisp.service;

import com.neuroisp.dto.PppoeUserDetailsDTO;
import com.neuroisp.dto.PppoeUserViewDTO;
import com.neuroisp.entity.*;
import com.neuroisp.repository.PppoePackageRepository;
import com.neuroisp.repository.PppoeSubscriptionRepository;
import com.neuroisp.sms.PppoeSmsMessageBuilder;
import com.neuroisp.sms.SmsService;
import com.neuroisp.sms.SubscriptionSmsBuilder;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PppoeSubscriptionService {

    private final PppoeSubscriptionRepository subscriptionRepo;
    private final PppoePackageRepository packageRepo;
    private final MikrotikPppoeService mikrotikService;
    private final SmsService smsService;
    private final SubscriptionSmsBuilder smsBuilder;
    private final IspCompanyService ispCompanyService;
    private final BillingService billingService; // ✅ ADD THIS


    // Step 1: user selects package
    public PppoeSubscription createPending(PppoeUser user, String packageId) {

        PppoePackage pkg = packageRepo.findById(packageId)
                .orElseThrow(() -> new RuntimeException("Package not found"));

        PppoeSubscription sub = subscriptionRepo.save(
                PppoeSubscription.builder()
                        .user(user)
                        .pppoePackage(pkg)
                        .router(user.getRouter())
                        .status(SubscriptionStatus.PENDING)
                        .build()
        );

        // ✅ CREATE BILLING CREDIT
        billingService.createSubscriptionCharge(sub);

        return sub;
    }


    // Step 2: payment received
    public PppoeSubscription markAsPaid(
            String subscriptionId,
            String receipt,
            boolean autoActivate
    ) {
        PppoeSubscription sub = subscriptionRepo.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));

        sub.setPaymentReference(receipt);

        if (autoActivate) {
            activate(sub);
        } else {
            sub.setStatus(SubscriptionStatus.PAID);
        }

        return subscriptionRepo.save(sub);
    }

    // Step 3: activate PPPoE
    // ACTIVATE INTERNET
    public PppoeSubscription activate(PppoeSubscription sub) {

        mikrotikService.createPppoeUser(
                sub.getRouter(),
                sub.getUser(),
                sub.getPppoePackage()
        );
        sub.setStatus(SubscriptionStatus.PAID);

        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartTime(LocalDateTime.now());
        sub.setExpiryTime(LocalDateTime.now().plusMonths(1));

        subscriptionRepo.save(sub);


        IspCompany isp = ispCompanyService.getActiveCompany();
        try {
        smsService.send(
                isp,
                sub.getUser().getPhoneNumber(),
                PppoeSmsMessageBuilder.buildActivationMessage(isp, sub)
        );
        } catch (Exception ignored) {}


        return sub;
    }
    public PppoeSubscription createByAdmin(
            PppoeUser user,
            PppoePackage pkg,
            boolean activateNow
    ) {
        PppoeSubscription sub = subscriptionRepo.save(
                PppoeSubscription.builder()
                        .user(user)
                        .pppoePackage(pkg)
                        .router(user.getRouter())
                        .status(activateNow ? SubscriptionStatus.ACTIVE : SubscriptionStatus.PENDING)
                        .build()
        );

        // ✅ BILL THE USER IMMEDIATELY
        billingService.createSubscriptionCharge(sub);

        if (activateNow) {
            activate(sub);
        }

        return sub;
    }

    public PppoeSubscription activateLater(String subscriptionId) {

        PppoeSubscription sub = subscriptionRepo.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));

        if (sub.getStatus() != SubscriptionStatus.PENDING &&
                sub.getStatus() != SubscriptionStatus.PAID) {
            throw new RuntimeException("Subscription cannot be activated");
        }

        return activate(sub);
    }
    public PppoeSubscription upgradePackage(
            String subscriptionId,
            String newPackageId
    ) {
        PppoeSubscription sub = subscriptionRepo.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));

        if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
            throw new RuntimeException("Only ACTIVE subscriptions can be upgraded");
        }

        PppoePackage newPkg = packageRepo.findById(newPackageId)
                .orElseThrow(() -> new RuntimeException("Package not found"));
        // 🔥 1️⃣ CREATE BILLING CHARGE
        billingService.createUpgradeCharge(sub, newPkg);
        // 🔥 Update MikroTik profile
        mikrotikService.updateProfile(
                sub.getRouter(),
                sub.getUser().getUsername(),
                sub.getUser().getPassword(),
                newPkg.getMikrotikProfile()
        );

        // Update DB
        sub.setPppoePackage(newPkg);

        subscriptionRepo.save(sub);


        // 📩 Notify user
        IspCompany isp = ispCompanyService.getActiveCompany();
        smsService.send(
                isp,
                sub.getUser().getPhoneNumber(),
                "Your internet package has been upgraded to "
                        + newPkg.getName()
                        + ". Enjoy faster speeds 🚀"
        );

        return sub;
    }
    public List<PppoeUserViewDTO> getUsersWithService() {
        return subscriptionRepo.fetchUsersWithSubscription();
    }
    @Transactional

    public void handlePaymentAndAutoActivate(
            PppoeUser user,
            Double amount,
            PaymentMethod method,
            String paymentReference
    ) {
        // 1️⃣ Record payment
        billingService.recordPayment(
                user,
                amount,
                method,
                paymentReference,
                "PPPoE subscription payment"
        );

        // 2️⃣ Get current balance
        double balance = billingService.getBalance(user.getId());

        if (balance > 0) {
            // Still owes money → nothing to activate
            return;
        }

        // 3️⃣ Find oldest unpaid subscriptions
        List<PppoeSubscription> subs =
                subscriptionRepo.findByUserIdAndStatusInOrderByStartTimeAsc(
                        user.getId(),
                        List.of(
                                SubscriptionStatus.PENDING,
                                SubscriptionStatus.PAID,
                                SubscriptionStatus.EXPIRED
                        )
                );

        for (PppoeSubscription sub : subs) {

            if (billingService.getBalance(user.getId()) > 0) {
                // Balance exhausted → stop
                break;
            }

            if (sub.getStatus() == SubscriptionStatus.EXPIRED) {
                reactivateExpired(sub);
            } else {
                sub.setStatus(SubscriptionStatus.PAID);
                activate(sub);
            }
        }
    }


    @Transactional
    public void suspendExpiredUnpaid() {

        List<PppoeSubscription> expired =
                subscriptionRepo.findByStatusAndExpiryTimeBefore(
                        SubscriptionStatus.ACTIVE,
                        LocalDateTime.now()
                );

        for (PppoeSubscription sub : expired) {

            double balance = billingService.getBalance(sub.getUser().getId());

            if (balance > 0) {
                // ⛔ Disable PPPoE
                mikrotikService.disablePppoeUser(
                        sub.getRouter(),
                        sub.getUser().getUsername()
                );

                sub.setStatus(SubscriptionStatus.EXPIRED);
                subscriptionRepo.save(sub);

                // 📩 Notify user
                IspCompany isp = ispCompanyService.getActiveCompany();
                smsService.send(
                        isp,
                        sub.getUser().getPhoneNumber(),
                        "Your internet has been suspended due to unpaid balance of KES "
                                + balance + ". Please pay to restore service."
                );
            }
        }
    }

    public PppoeUserDetailsDTO getUserDetails(String userId) {

        PppoeSubscription sub = subscriptionRepo
                .findTopByUserIdOrderByExpiryTimeDesc(userId)
                .orElseThrow(() -> new RuntimeException("No subscription found"));

        double balance = billingService.getBalance(userId);
        PppoePackage pkg = sub.getPppoePackage();

        return PppoeUserDetailsDTO.builder()
                .userId(sub.getUser().getId())
                .fullName(sub.getUser().getFullName())
                .email(sub.getUser().getEmail())
                .phone(sub.getUser().getPhoneNumber())
                .username(sub.getUser().getUsername())

                .subscriptionId(sub.getId())
                .packageName(pkg.getName())

                // ✅ SPEEDS COME FROM PACKAGE
                .downloadSpeed(pkg.getDownloadSpeed())
                .uploadSpeed(pkg.getUploadSpeed())

                .status(sub.getStatus().name())
                .activationDate(sub.getStartTime())
                .expiryDate(sub.getExpiryTime())

                .balance(balance)
                .build();
    }
    @Transactional
    public PppoeSubscription updateExpiryDate(
            String subscriptionId,
            LocalDateTime newExpiryTime
    ) {
        PppoeSubscription sub = subscriptionRepo.findById(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Subscription not found"));

        if (newExpiryTime.isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Expiry date cannot be in the past");
        }

        sub.setExpiryTime(newExpiryTime);

        // If subscription was expired but admin extends it → reactivate
        if (sub.getStatus() == SubscriptionStatus.EXPIRED) {
            sub.setStatus(SubscriptionStatus.ACTIVE);

            // Re-enable PPPoE on MikroTik
            mikrotikService.enablePppoeUser(
                    sub.getRouter(),
                    sub.getUser().getUsername()
            );
        }

        return subscriptionRepo.save(sub);
    }
    @Transactional
    public PppoeSubscription reactivateExpired(PppoeSubscription sub) {

        // Re-enable PPPoE
        mikrotikService.enablePppoeUser(
                sub.getRouter(),
                sub.getUser().getUsername()
        );

        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartTime(LocalDateTime.now());

        // Extend from NOW
        sub.setExpiryTime(LocalDateTime.now().plusMonths(1));

        subscriptionRepo.save(sub);

        IspCompany isp = ispCompanyService.getActiveCompany();
        smsService.send(
                isp,
                sub.getUser().getPhoneNumber(),
                "Your internet service has been restored. Thank you for your payment."
        );

        return sub;
    }

}
