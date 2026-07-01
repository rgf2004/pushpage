package me.projects.pushpage.service;

import org.springframework.stereotype.Service;

/**
 * Default {@link UserLifecycleHooks} implementation used when no other bean overrides it.
 * Self-hosted deployments run entirely on this no-op — sign-up and login behavior is
 * unaffected unless another module provides an {@code @Primary} implementation.
 */
@Service
public class NoOpUserLifecycleHooks implements UserLifecycleHooks {
}
