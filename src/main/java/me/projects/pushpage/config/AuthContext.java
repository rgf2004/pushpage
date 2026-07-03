package me.projects.pushpage.config;

import me.projects.pushpage.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AuthContext {

    private static final ThreadLocal<User> holder = new ThreadLocal<>();

    public void setCurrentUser(User user) {
        holder.set(user);
    }

    public User getCurrentUser() {
        return holder.get();
    }

    public User requireCurrentUser() {
        User user = holder.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return user;
    }

    public boolean isAdmin() {
        User user = holder.get();
        return user != null && user.admin();
    }

    public void clear() {
        holder.remove();
    }
}
