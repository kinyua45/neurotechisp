package com.neuroisp.service;



import com.neuroisp.entity.MikrotikRouter;
import com.neuroisp.entity.PppoePackage;
import com.neuroisp.entity.PppoeUser;
import lombok.RequiredArgsConstructor;
import me.legrange.mikrotik.ApiConnection;
import me.legrange.mikrotik.ApiConnectionException;
import me.legrange.mikrotik.MikrotikApiException;
import me.legrange.mikrotik.ResultListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MikrotikPppoeService {



    public void createPppoeUser(
            MikrotikRouter router,
            PppoeUser user,
            PppoePackage pkg
    ) {
        try (ApiConnection api = ApiConnection.connect(router.getIpAddress())) {

            api.login(router.getUsername(), router.getPassword());
            System.out.println(router.getUsername());
            System.out.println(router.getPassword());
            String command = String.format(
                    "/ppp/secret/add name=%s password=%s service=pppoe profile=%s",
                    user.getUsername(),
                    user.getPassword(),
                    pkg.getMikrotikProfile()
            );

            api.execute(command);

        } catch (Exception e) {
            throw new RuntimeException("Failed to create PPPoE user on MikroTik", e);
        }
    }


    public void disablePppoeUser(MikrotikRouter router, String username) {

        try (ApiConnection api = ApiConnection.connect(router.getIpAddress())) {

            api.login(router.getUsername(), router.getPassword());
            // 1️⃣ Get all secrets
            List<Map<String, String>> secrets =
                    api.execute("/ppp/secret/print");

            // 2️⃣ Find matching user
            String secretId = secrets.stream()
                    .filter(s -> username.equals(s.get("name")))
                    .map(s -> s.get(".id"))
                    .findFirst()
                    .orElseThrow(() ->
                            new RuntimeException("PPPoE user not found on MikroTik: " + username)
                    );

            String command = String.format(
                    "/ppp/secret/set .id=%s disabled=yes",
                    secretId,
                    username
            );

            api.execute(command);

        } catch (Exception e) {
            throw new RuntimeException("Failed to disable PPPoE user", e);
        }
    }
    public void updateProfile(
            MikrotikRouter router,
            String username,
            String password,
            String newProfile
    ) {
        try (ApiConnection api = ApiConnection.connect(router.getIpAddress())) {

            api.login(router.getUsername(), router.getPassword());

            // 1️⃣ Get all secrets
            List<Map<String, String>> secrets =
                    api.execute("/ppp/secret/print");

            // 2️⃣ Find matching user
            String secretId = secrets.stream()
                    .filter(s -> username.equals(s.get("name")))
                    .map(s -> s.get(".id"))
                    .findFirst()
                    .orElseThrow(() ->
                            new RuntimeException("PPPoE user not found on MikroTik: " + username)
                    );

            // 3️⃣ Update profile using .id
            String command = String.format(
                    "/ppp/secret/set .id=%s profile=%s",
                   secretId,
                 newProfile
            );

            api.execute(command);

        } catch (Exception e) {
            throw new RuntimeException("Failed to update PPPoE profile", e);
        }
    }

    // ✅ ADD THIS METHOD (nothing else changes)
    public void enablePppoeUser(MikrotikRouter router, String username) {

        try (ApiConnection api = ApiConnection.connect(router.getIpAddress())) {

            api.login(router.getUsername(), router.getPassword());

            List<Map<String, String>> secrets =
                    api.execute("/ppp/secret/print");

            // 2️⃣ Find matching user
            String secretId = secrets.stream()
                    .filter(s -> username.equals(s.get("name")))
                    .map(s -> s.get(".id"))
                    .findFirst()
                    .orElseThrow(() ->
                            new RuntimeException("PPPoE user not found on MikroTik: " + username)
                    );

            String command = String.format(
                    "/ppp/secret/set .id=%s disabled=no",
                    secretId,
                    username
            );

            api.execute(command);

        } catch (Exception e) {
            throw new RuntimeException("Failed to enable PPPoE user", e);
        }
    }

}
