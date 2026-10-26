package com.safehaven.service;

import com.safehaven.model.User;

public interface IAuthService {
    boolean register(String username, String password);

    /**
     * Registers a user from a mutable password buffer.
     *
     * <p>Implementations validate the inputs and wipe the supplied array when
     * they are done with it.</p>
     */
    boolean register(String username, char[] password);

    User login(String username, String password);
}
