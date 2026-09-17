package com.catalogix.user.config;

import com.catalogix.user.model.User;
import com.catalogix.user.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminSeederTest {

    @Mock private UserRepository repo;
    @Mock private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void doesNothingWhenSeedingIsDisabled(){
        AdminSeeder seeder = new AdminSeeder(repo, passwordEncoder, false, "admin@catalogix.local", "pw");
        seeder.run();
        verifyNoInteractions(repo);
    }

    @Test
    void doesNothingWhenPasswordIsBlank(){
        AdminSeeder seeder = new AdminSeeder(repo, passwordEncoder, true, "admin@catalogix.local", "");
        seeder.run();
        verifyNoInteractions(repo);
    }

    @Test
    void doesNothingWhenAnAdminWithThatEmailAlreadyExists(){
        when(repo.findByEmail("admin@catalogix.local")).thenReturn(Optional.of(new User()));
        AdminSeeder seeder = new AdminSeeder(repo, passwordEncoder, true, "admin@catalogix.local", "pw");

        seeder.run();

        verify(repo, never()).save(any());
    }

    @Test
    void createsAVerifiedAdminWhenEnabledWithAPasswordAndNoExistingAccount(){
        when(repo.findByEmail("admin@catalogix.local")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("pw")).thenReturn("hashed-pw");
        AdminSeeder seeder = new AdminSeeder(repo, passwordEncoder, true, "admin@catalogix.local", "pw");

        seeder.run();

        verify(repo).save(argThat(u ->
                u.getEmail().equals("admin@catalogix.local")
                        && u.getPassword().equals("hashed-pw")
                        && u.getRole().equals("ADMIN")
                        && u.isVerified()));
    }
}
