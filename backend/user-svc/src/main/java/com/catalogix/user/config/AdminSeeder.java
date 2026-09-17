package com.catalogix.user.config;

import com.catalogix.user.model.User;
import com.catalogix.user.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates a known admin account on startup so "docker compose up" / a fresh
 * `helm install` always leaves you with a working admin login, instead of
 * having to register-then-match-ADMIN_EMAILS by hand in every fresh
 * environment.
 *
 * Off by default (SEED_DATA=false) — this must be opted into per
 * environment, never assumed. Idempotent: checks for the email first, so
 * it's safe to run on every restart rather than just "first boot".
 *
 * SEED_ADMIN_PASSWORD has NO default — if SEED_DATA is true but the
 * password is blank, this logs a warning and skips rather than either
 * failing startup or (worse) silently creating an admin account with an
 * empty/guessable password. Local/compose and values-local.yaml set a
 * fixed password directly, since those never leave your machine. For
 * anything AWS-facing (values-dev.yaml, values-staging.yaml), it's
 * deliberately left unset in the committed file — supply it with
 * `helm install --set` at deploy time, same pattern already used for
 * database.host and ingress.tlsCertArn, so a real password is never sitting
 * in git even for a dev/staging AWS environment.
 */
@Component
public class AdminSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository repo;
    private final PasswordEncoder passwordEncoder;
    private final boolean seedEnabled;
    private final String seedAdminEmail;
    private final String seedAdminPassword;

    public AdminSeeder(
            UserRepository repo,
            PasswordEncoder passwordEncoder,
            @Value("${SEED_DATA:false}") boolean seedEnabled,
            @Value("${SEED_ADMIN_EMAIL:admin@catalogix.local}") String seedAdminEmail,
            @Value("${SEED_ADMIN_PASSWORD:}") String seedAdminPassword
    ) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.seedEnabled = seedEnabled;
        this.seedAdminEmail = seedAdminEmail;
        this.seedAdminPassword = seedAdminPassword;
    }

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            return;
        }
        if (seedAdminPassword == null || seedAdminPassword.isBlank()) {
            log.warn("SEED_DATA is true but SEED_ADMIN_PASSWORD is blank — skipping admin seed. "
                    + "Set it explicitly (helm install --set for AWS environments).");
            return;
        }
        if (repo.findByEmail(seedAdminEmail).isPresent()) {
            return; // already seeded on a previous startup — nothing to do
        }

        User admin = new User();
        admin.setName("Admin");
        admin.setEmail(seedAdminEmail);
        admin.setPassword(passwordEncoder.encode(seedAdminPassword));
        admin.setRole("ADMIN");
        // Seeded, not self-registered — there's no inbox to click a
        // verification link from, so start it pre-verified rather than
        // permanently stuck behind the "email not verified" banner.
        admin.setVerified(true);
        repo.save(admin);

        log.info("Seeded admin account: {}", seedAdminEmail);
    }
}
