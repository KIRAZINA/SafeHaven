package com.safehaven.service;

import com.safehaven.model.User;

public interface IAuthService {
    boolean register(String username, String password);
    User login(String username, String password);
}
