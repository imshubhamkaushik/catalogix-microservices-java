package com.catalogix.user.config;

import com.catalogix.user.model.User;
import com.catalogix.user.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Guarantees that a fresh deployment starts with a working admin login.
 *
 * Roles are approval-based: everyone who registers is a customer, and only an
 * admin can grant the seller or admin role. So a deployment with no admin is a
 * dead end — nobody could ever approve a seller. This runner closes that gap:
 * on every startup, IF NO ADMIN EXISTS YET, it creates one from
 * SEED_ADMIN_EMAIL / SEED_ADMIN_PASSWORD. It is deliberately independent of
 * SEED_DATA (which only controls demo catalogue data): the first admin is a
 * requirement of every deployment, not demo content.
 *
 * Behaviour:
 *  - an admin already exists (this one, or anyone later promoted)  -> nothing to do;
 *    changing SEED_ADMIN_PASSWORD later does NOT touch an existing account;
 *  - no admin and a usable password  -> the admin is created, pre-verified;
 *  - no admin and NO password        -> logs an ERROR (there is then no way to
 *    manage roles) and starts anyway rather than crash-looping the service;
 *  - no admin, but the seed email is already taken by an ordinary account -> logs an
 *    ERROR and does NOT promote it (that account belongs to whoever registered it
 *    and knows THEIR password, not the operator's); pick another SEED_ADMIN_EMAIL.
 *
 * Where the password comes from: Docker Compose and values-local.yaml set a fixed
 * one (those never leave your machine). On AWS (values-dev.yaml) it is the
 * `seed_admin_password` key of the "<cluster>/operator-credentials" secret — the password
 * chosen by whoever deploys the app, with `python bootstrap.py credentials`. Nothing is
 * committed to git, generated behind their back, or passed as a pipeline parameter.
 */
@Component
public class AdminSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository repo;
    private final PasswordEncoder passwordEncoder;
    private final String seedAdminEmail;
    private final String seedAdminPassword;

    public AdminSeeder(
            UserRepository repo,
            PasswordEncoder passwordEncoder,
            @Value("${SEED_ADMIN_EMAIL:admin@catalogix.local}") String seedAdminEmail,
            @Value("${SEED_ADMIN_PASSWORD:}") String seedAdminPassword
    ) {
        this.repo = repo;
        this.passwordEncoder = passwordEncoder;
        this.seedAdminEmail = seedAdminEmail;
        this.seedAdminPassword = seedAdminPassword;
    }

    @Override
    public void run(String... args) {
        if (repo.countByRole("ADMIN") > 0) {
            return; // an admin already exists — nothing to do on this or any later startup
        }
        if (seedAdminPassword == null || seedAdminPassword.length() < MIN_PASSWORD_LENGTH) {
            log.error("NO ADMIN ACCOUNT EXISTS and SEED_ADMIN_PASSWORD is not set (or shorter than {} "
                    + "characters). Nobody will be able to approve sellers or assign roles. Provide "
                    + "SEED_ADMIN_PASSWORD (on AWS: the seed_admin_password key of the app secret) "
                    + "and restart user-svc.", MIN_PASSWORD_LENGTH);
            return;
        }
        if (repo.findByEmail(seedAdminEmail).isPresent()) {
            log.error("NO ADMIN ACCOUNT EXISTS, but {} is already registered as an ordinary account. It "
                    + "is NOT being promoted (it belongs to whoever registered it). Set SEED_ADMIN_EMAIL "
                    + "to an unused address and restart user-svc.", seedAdminEmail);
            return;
        }

        User admin = new User();
        admin.setName("Admin");
        admin.setEmail(seedAdminEmail);
        admin.setPassword(passwordEncoder.encode(seedAdminPassword));
        admin.setRole("ADMIN");
        // Seeded, not self-registered — there's no inbox to click a verification link
        // from, so start it pre-verified rather than stuck behind the "email not
        // verified" banner.
        admin.setVerified(true);
        try {
            repo.save(admin);
            log.info("Created the bootstrap admin account: {}", seedAdminEmail);
        } catch (DataIntegrityViolationException e) {
            // Several replicas start at once on a fresh deployment; another one won the race.
            log.info("Bootstrap admin {} was created by another replica.", seedAdminEmail);
        }
    }
}
