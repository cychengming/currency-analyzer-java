package com.currencyanalyzer.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeAdminServiceTests {

    @Test
    void emptyAdminListTreatsOldestUserAsOwner() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findOldest()).thenReturn(Optional.of(
            new UserAccount(1L, "Alice", "hash", Instant.parse("2026-11-22T12:00:00Z"))
        ));

        KnowledgeAdminService service = new KnowledgeAdminService(repository, "  ");

        assertFalse(service.isReservedAdminName("admin"));
        assertTrue(service.isAdminUsername("alice"));
        assertTrue(service.isAdminUsername(" Alice "));
        assertFalse(service.isAdminUsername("bob"));
    }

    @Test
    void configuredNamesAreAdminWithoutFallingBackToTheOldestUser() {
        UserRepository repository = mock(UserRepository.class);
        KnowledgeAdminService service = new KnowledgeAdminService(repository, "Admin, ops");

        assertTrue(service.isReservedAdminName("admin"));
        assertTrue(service.isAdminUsername("ADMIN"));
        assertTrue(service.isAdminUsername("ops"));
        assertFalse(service.isAdminUsername("alice"));
        verify(repository, never()).findOldest();
    }
}
