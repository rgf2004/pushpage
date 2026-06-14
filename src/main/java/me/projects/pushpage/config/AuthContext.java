package me.projects.pushpage.config;

import me.projects.pushpage.model.User;
import org.springframework.stereotype.Component;

@Component
public class AuthContext {

    private static final ThreadLocal<User> holder = new ThreadLocal<>();

    public void setCurrentUser(User user) {
        holder.set(user);
    }

    public User getCurrentUser() {
        return holder.get();
    }

    public boolean isAdmin() {
        User user = holder.get();
        return user != null && user.admin();
    }

    public void clear() {
        holder.remove();
    }
}
