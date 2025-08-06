package fr.ses10doigts.tradeIO5.security.model.payload.request;

import java.util.List;
import java.util.Set;

import fr.ses10doigts.tradeIO5.security.model.Role;
import fr.ses10doigts.tradeIO5.security.validation.PasswordMatches;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@PasswordMatches(message = "Passwords don't match")
public class SignupRequest {
    @NotBlank(message = "Username can't be empty")
    @Size(min = 3, max = 20, message = "Username must be between 3 and 20 characters")
    private String    username;

    @NotBlank(message = "Email can't be empty")
    @Size(max = 50, message = "Email must be less than 20 characters")
    @Email(message = "Email isn't on the rigth format")
    private String    email;

    @NotBlank(message = "Password can't be empty")
    @Size(min = 6, max = 40, message = "Password must be between 6 and 40 characters")
    private String    password;

    @NotBlank(message = "Matching password can't be empty")
    @Size(min = 6, max = 40, message = "Password must be between 6 and 40 characters")
    private String    verifPassword;

    private Set<Role>	 roles;
    private List<String> role;

    public String getUsername() {
	return username;
    }

    public void setUsername(String username) {
	this.username = username;
    }

    public String getEmail() {
	return email;
    }

    public void setEmail(String email) {
	this.email = email;
    }

    public String getPassword() {
	return password;
    }

    public void setPassword(String password) {
	this.password = password;
    }

    public String getVerifPassword() {
	return verifPassword;
    }

    public void setVerifPassword(String verifPassword) {
	this.verifPassword = verifPassword;
    }

    public Set<Role> getRoles() {
	return roles;
    }

    public void setRoles(Set<Role> roles) {
	this.roles = roles;
    }

    public List<String> getRole() {
	return role;
    }

    public void setRole(List<String> role) {
	this.role = role;
    }
}