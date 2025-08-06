package fr.ses10doigts.tradeIO5.security.model.payload.request;

import jakarta.validation.constraints.NotBlank;

public class LoginRequest {

    @NotBlank(message = "Username can't be empty")
    private String userName;

    @NotBlank(message = "Password can't be empty")
    private String password;

    public String getUserName() {
	return userName;
    }

    public void setUserName(String username) {
	this.userName = username;
    }

    public String getPassword() {
	return password;
    }

    public void setPassword(String password) {
	this.password = password;
    }
}