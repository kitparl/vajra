package com.vajra.gatewaybff.auth;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Reads a password from stdin and prints only its bcrypt hash (cost 12). Used by scripts/gateway-password-hash.sh. */
public final class PasswordHashCli {

    private PasswordHashCli() {}

    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String password = in.readLine();
        if (password == null || password.isEmpty()) {
            System.err.println("empty password");
            System.exit(1);
        }
        System.out.println(new BCryptPasswordEncoder(SecurityConfig.BCRYPT_COST).encode(password));
    }
}
